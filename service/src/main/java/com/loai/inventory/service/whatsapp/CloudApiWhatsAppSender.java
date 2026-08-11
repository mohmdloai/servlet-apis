package com.loai.inventory.service.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Meta WhatsApp Cloud API transport: {@code POST /{version}/{phone_number_id}/messages} with the
 * merchant's own access token.
 *
 * <p>Built on the JDK's {@link HttpClient} — already the codebase's HTTP client ({@code
 * PresignedLogoSource}, {@code PresignedOgImageSource}), so this channel adds <b>no dependency</b>.
 *
 * <p><b>Bounded, like the SMTP sender.</b> Connect and request timeouts are set explicitly: the
 * sweeper calls this outside the delivery row's lock and outside any transaction (the D4 claim →
 * send → settle split), but an unbounded call would still pin a worker thread forever, and a lease
 * reaper that fires before the call returns would send the message twice. The timeouts must stay
 * comfortably under {@code EMAIL_CLAIM_LEASE_SECONDS}' WhatsApp equivalent.
 *
 * <p><b>Retryable vs terminal.</b> A 4xx that is not 429 means Meta rejected the message itself —
 * an unapproved template, a number that is not on WhatsApp, a revoked token — and retrying it four
 * more times only burns the budget and delays the FAILED that tells someone to look. Those throw
 * {@link TerminalWhatsAppException}, which the settle step fails immediately. 429 and 5xx are
 * transient and take the ordinary retry path.
 *
 * <p><b>The token never leaves this class</b> as plaintext: it is decrypted per send, used as a
 * bearer header, and never logged. Error logs carry the provider's message, which Meta does not
 * echo credentials into.
 */
public final class CloudApiWhatsAppSender implements WhatsAppSender {

  private static final Logger log = LoggerFactory.getLogger(CloudApiWhatsAppSender.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Meta's Graph API version. Pinned: an unpinned version changes the contract under us. */
  static final String GRAPH_VERSION = "v21.0";

  private static final String GRAPH_BASE = "https://graph.facebook.com";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

  private final HttpClient http;
  private final SecretBox secretBox;
  private final String baseUrl;

  public CloudApiWhatsAppSender(SecretBox secretBox) {
    this(secretBox, GRAPH_BASE);
  }

  /** Visible for test: point at a local stub instead of Meta. */
  CloudApiWhatsAppSender(SecretBox secretBox, String baseUrl) {
    this.secretBox = secretBox;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  /** A rejection that will never succeed on retry — the settle step marks it FAILED at once. */
  public static final class TerminalWhatsAppException extends WhatsAppException {
    public TerminalWhatsAppException(String message) {
      super(message);
    }
  }

  @Override
  public String send(OrgWhatsAppConfig config, WhatsAppMessage message) {
    String url = baseUrl + "/" + GRAPH_VERSION + "/" + config.phoneNumberId() + "/messages";
    String token = secretBox.decrypt(config.accessTokenEncrypted());

    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body(message)))
              .build();
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WhatsAppException("interrupted while sending", e);
    } catch (Exception e) {
      // Transport fault — retryable, so deliberately NOT terminal.
      throw new WhatsAppException("could not reach the WhatsApp Cloud API", e);
    }

    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return messageIdOf(response.body());
    }
    String detail = providerError(response.body());
    if (status == 429 || status >= 500) {
      throw new WhatsAppException("WhatsApp API " + status + ": " + detail);
    }
    // 4xx other than 429: the message itself was refused. Retrying cannot fix an unapproved
    // template or a number that is not on WhatsApp.
    log.warn(
        "WhatsApp rejected a message for org {} (template {}): {}",
        config.orgId(),
        message.templateName(),
        detail);
    throw new TerminalWhatsAppException("WhatsApp API " + status + ": " + detail);
  }

  /** The Cloud API's template-message payload. */
  static String body(WhatsAppMessage message) {
    ObjectNode root = JSON.createObjectNode();
    root.put("messaging_product", "whatsapp");
    root.put("to", message.toNumber());
    root.put("type", "template");

    ObjectNode template = root.putObject("template");
    template.put("name", message.templateName());
    template.putObject("language").put("code", message.languageCode());

    if (message.bodyParams() != null && !message.bodyParams().isEmpty()) {
      ArrayNode components = template.putArray("components");
      ObjectNode bodyComponent = components.addObject();
      bodyComponent.put("type", "body");
      ArrayNode params = bodyComponent.putArray("parameters");
      for (String value : message.bodyParams()) {
        ObjectNode p = params.addObject();
        p.put("type", "text");
        // Meta rejects a null parameter outright; an empty string is the honest stand-in for a
        // value the payload did not carry, and the templates only pass required fields anyway.
        p.put("text", value == null ? "" : value);
      }
    }
    return root.toString();
  }

  private static String messageIdOf(String responseBody) {
    try {
      JsonNode messages = JSON.readTree(responseBody).path("messages");
      if (messages.isArray() && !messages.isEmpty()) {
        String id = messages.get(0).path("id").asText(null);
        return (id == null || id.isBlank()) ? null : id;
      }
    } catch (Exception e) {
      // A 2xx we could not parse still means "accepted" — do not fail the send over the id.
      log.debug("Could not read a message id from a successful WhatsApp response", e);
    }
    return null;
  }

  private static String providerError(String responseBody) {
    try {
      JsonNode error = JSON.readTree(responseBody).path("error");
      String msg = error.path("message").asText(null);
      String code = error.path("code").asText(null);
      if (msg != null) {
        return code == null ? msg : msg + " (code " + code + ")";
      }
    } catch (Exception ignored) {
      // fall through to the raw body
    }
    return truncate(responseBody);
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 300 ? s : s.substring(0, 300);
  }
}
