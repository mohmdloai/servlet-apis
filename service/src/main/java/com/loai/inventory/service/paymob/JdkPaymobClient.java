package com.loai.inventory.service.paymob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loai.inventory.common.exception.UpstreamFailureException;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PaymobClient} on the JDK's {@link HttpClient} — the codebase's HTTP client, as for
 * WhatsApp and Web Push.
 *
 * <p><b>Bounded, and never retried.</b> Connect and request timeouts are explicit ({@code
 * PAYMOB_HTTP_TIMEOUT_MS}); a timeout surfaces as a 502 to the shopper, who taps again and gets a
 * fresh intention. Retrying inside this class would be worse than that: a request that reached
 * Paymob and timed out on the way back has already created an intention, and a retry creates a
 * second one under a second {@code special_reference} — or a 4xx for a duplicate, which tells us
 * nothing about the first.
 *
 * <p>The merchant's secret key travels only in the {@code Authorization} header of this one call
 * and is never logged; on a rejection the response body is logged at WARN (it carries Paymob's
 * validation message, never our credential).
 */
public final class JdkPaymobClient implements PaymobClient {

  private static final Logger log = LoggerFactory.getLogger(JdkPaymobClient.class);

  private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_LOGGED_BODY = 500;

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final Duration requestTimeout;
  private final Function<String, String> hostForRegion;

  public JdkPaymobClient(ObjectMapper mapper, Duration timeout) {
    this(mapper, timeout, PaymobHosts::forRegion);
  }

  /** Test seam: point a region at a stub server. */
  public JdkPaymobClient(
      ObjectMapper mapper, Duration timeout, Function<String, String> hostForRegion) {
    this.mapper = mapper;
    this.requestTimeout = timeout;
    this.hostForRegion = hostForRegion;
    Duration connect = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
    this.http = HttpClient.newBuilder().connectTimeout(connect).build();
  }

  @Override
  public IntentionResult createIntention(
      OrgPaymobConfig config, String secretKey, IntentionRequest req) {
    String url = hostForRegion.apply(config.region()) + "/v1/intention/";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Token " + secretKey)
            .POST(HttpRequest.BodyPublishers.ofString(body(req)))
            .build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      log.warn("Paymob intention call failed for org {}: {}", config.orgId(), e.toString());
      throw new UpstreamFailureException("payment provider did not answer", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamFailureException("payment provider call interrupted", e);
    }

    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      log.warn(
          "Paymob rejected the intention for org {} (HTTP {}): {}",
          config.orgId(),
          status,
          truncate(response.body()));
      throw new UpstreamFailureException("payment provider rejected the payment request");
    }
    return parse(config, response.body());
  }

  @Override
  public String authenticate(OrgPaymobConfig config, String apiKey) {
    ObjectNode body = mapper.createObjectNode();
    body.put("api_key", apiKey);
    HttpResponse<String> response =
        send(
            config,
            HttpRequest.newBuilder(
                    URI.create(hostForRegion.apply(config.region()) + "/api/auth/tokens"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            "auth token");
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      log.warn(
          "Paymob refused the auth-token exchange for org {} (HTTP {}): {}",
          config.orgId(),
          status,
          truncate(response.body()));
      throw new UpstreamFailureException("payment provider rejected the API key");
    }
    JsonNode root = readTree(response.body());
    String token = root == null ? null : textOrNull(root.path("token"));
    if (token == null) {
      throw new UpstreamFailureException("payment provider answered without an auth token");
    }
    return token;
  }

  @Override
  public Optional<String> inquireTransaction(
      OrgPaymobConfig config, String authToken, String paymobOrderId, String merchantOrderId) {
    ObjectNode body = mapper.createObjectNode();
    if (paymobOrderId != null && !paymobOrderId.isBlank()) {
      body.put("order_id", paymobOrderId);
    } else {
      body.put("merchant_order_id", merchantOrderId);
    }
    HttpResponse<String> response =
        send(
            config,
            HttpRequest.newBuilder(
                    URI.create(
                        hostForRegion.apply(config.region())
                            + "/api/ecommerce/orders/transaction_inquiry"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            "transaction inquiry");
    int status = response.statusCode();
    if (status == 404) {
      return Optional.empty(); // no transaction for this order yet
    }
    if (status == 401 || status == 403) {
      log.warn(
          "Paymob rejected the inquiry token for org {} (HTTP {}): {}",
          config.orgId(),
          status,
          truncate(response.body()));
      throw new UpstreamFailureException("payment provider rejected the inquiry token");
    }
    if (status < 200 || status >= 300) {
      log.warn(
          "Paymob inquiry failed for org {} (HTTP {}): {}",
          config.orgId(),
          status,
          truncate(response.body()));
      throw new UpstreamFailureException("payment provider inquiry failed");
    }
    JsonNode root = readTree(response.body());
    if (root == null || !root.isObject() || textOrNull(root.path("id")) == null) {
      // {"detail": "…"} with a 200, or an empty object: nothing to settle from.
      return Optional.empty();
    }
    return Optional.of(response.body());
  }

  private HttpResponse<String> send(OrgPaymobConfig config, HttpRequest request, String what) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      log.warn("Paymob {} call failed for org {}: {}", what, config.orgId(), e.toString());
      throw new UpstreamFailureException("payment provider did not answer", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamFailureException("payment provider call interrupted", e);
    }
  }

  private JsonNode readTree(String body) {
    try {
      return mapper.readTree(body == null ? "" : body);
    } catch (IOException e) {
      return null;
    }
  }

  private String body(IntentionRequest req) {
    ObjectNode root = mapper.createObjectNode();
    root.put("amount", req.amountCents());
    root.put("currency", req.currency());
    root.putArray("payment_methods").add(req.integrationId());
    root.put("special_reference", req.specialReference());
    root.put("notification_url", req.notificationUrl());
    root.put("redirection_url", req.redirectionUrl());
    root.put("expiration", req.expirationSeconds());
    ArrayNode items = root.putArray("items");
    for (Item item : req.items()) {
      ObjectNode it = items.addObject();
      it.put("name", item.name());
      it.put("amount", item.amountCents());
      it.put("quantity", item.quantity());
      if (item.description() != null) {
        it.put("description", item.description());
      }
    }
    Billing b = req.billing();
    ObjectNode billing = root.putObject("billing_data");
    billing.put("first_name", b.firstName());
    billing.put("last_name", b.lastName());
    billing.put("phone_number", b.phoneNumber());
    if (b.email() != null) {
      billing.put("email", b.email());
    }
    ObjectNode extras = root.putObject("extras");
    extras.put("org_id", req.orgId());
    extras.put("sales_order_id", req.salesOrderId());
    extras.put("intent_id", req.intentId());
    try {
      return mapper.writeValueAsString(root);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("could not serialise the intention request", e);
    }
  }

  private IntentionResult parse(OrgPaymobConfig config, String body) {
    JsonNode root;
    try {
      root = mapper.readTree(body == null ? "" : body);
    } catch (IOException e) {
      root = null;
    }
    if (root == null || !root.isObject()) {
      log.warn("Paymob answered the intention for org {} with no JSON object", config.orgId());
      throw new UpstreamFailureException("payment provider answered unexpectedly");
    }
    String clientSecret = textOrNull(root.path("client_secret"));
    if (clientSecret == null) {
      log.warn(
          "Paymob answered the intention for org {} without a client_secret: {}",
          config.orgId(),
          truncate(body));
      throw new UpstreamFailureException("payment provider answered without a checkout secret");
    }
    return new IntentionResult(
        textOrNull(root.path("id")), textOrNull(root.path("intention_order_id")), clientSecret);
  }

  private static String textOrNull(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return null;
    }
    String s = n.asText();
    return s == null || s.isBlank() ? null : s;
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= MAX_LOGGED_BODY ? s : s.substring(0, MAX_LOGGED_BODY) + "…";
  }
}
