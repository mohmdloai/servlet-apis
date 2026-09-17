package com.loai.inventory.service.storefront;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hook over the JDK {@link HttpClient} — no dependency added.
 *
 * <pre>
 * revalidate(slug, scopes):  POST url   Authorization: Bearer secret
 *                            {"org_slug": slug, "scopes": [wire…]}      -- sendAsync, caller returns
 *                            2xx → debug | else, timeout, IOException → WARN(slug, status)
 * </pre>
 *
 * Never retried (the storefront TTL is the retry) and the secret is never logged.
 */
public final class JdkStorefrontRevalidator implements StorefrontRevalidator {

  private static final Logger log = LoggerFactory.getLogger(JdkStorefrontRevalidator.class);

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final URI url;
  private final String secret;
  private final Duration timeout;

  public JdkStorefrontRevalidator(ObjectMapper mapper, String url, String secret) {
    this(mapper, url, secret, DEFAULT_TIMEOUT);
  }

  /** Visible for test: a short timeout against a stub that never answers. */
  JdkStorefrontRevalidator(ObjectMapper mapper, String url, String secret, Duration timeout) {
    this.mapper = mapper;
    this.url = URI.create(url);
    this.secret = secret;
    this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @Override
  public void revalidate(String orgSlug, Set<Scope> scopes) {
    send(orgSlug, scopes);
  }

  /** Visible for test: completes (never exceptionally) once the outcome has been logged. */
  CompletableFuture<Void> send(String orgSlug, Set<Scope> scopes) {
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("org_slug", orgSlug);
      List<String> wire = scopes.stream().sorted().map(Scope::wire).toList();
      body.put("scopes", wire);
      HttpRequest request =
          HttpRequest.newBuilder(url)
              .timeout(timeout)
              .header("Authorization", "Bearer " + secret)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      mapper.writeValueAsString(body), StandardCharsets.UTF_8))
              .build();
      return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .handle(
              (res, err) -> {
                if (err != null) {
                  log.warn(
                      "storefront revalidate failed for {} {}: {}", orgSlug, wire, err.toString());
                } else if (res.statusCode() / 100 != 2) {
                  log.warn(
                      "storefront revalidate refused for {} {}: HTTP {}",
                      orgSlug,
                      wire,
                      res.statusCode());
                } else {
                  log.debug("storefront revalidated {} {}", orgSlug, wire);
                }
                return null;
              });
    } catch (Exception e) {
      // A body that would not serialise, an executor that refused: still only a lost hint.
      log.warn("storefront revalidate not sent for {}: {}", orgSlug, e.toString());
      return CompletableFuture.completedFuture(null);
    }
  }
}
