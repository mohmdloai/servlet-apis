package com.loai.inventory.service.push;

import com.loai.inventory.common.crypto.WebPushEncryptor;
import com.loai.inventory.common.security.VapidSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Web Push transport over the JDK {@link HttpClient} — the {@code CloudApiWhatsAppSender} shape:
 * bounded timeouts, a retryable-vs-terminal status taxonomy, and a package-private constructor that
 * points at a local stub for the IT. No dependency added.
 *
 * <p>Per RFC 8030/8291/8292, one POST to the subscription's endpoint: the {@code aes128gcm} body
 * from {@link WebPushEncryptor} (a fresh ephemeral key and salt per message), {@code
 * Content-Encoding: aes128gcm}, {@code TTL: 86400} (a phone that is off for a day still gets it;
 * older than that the feed row has long since been read), {@code Urgency: normal}, and the VAPID
 * {@code Authorization} from {@link VapidSigner}.
 *
 * <p><b>Retryable vs terminal.</b> {@code 201}/{@code 200} → accepted. {@code 404}/{@code 410} →
 * the subscription is dead (unsubscribed, or the endpoint expired): terminal <em>and</em> {@code
 * gone}, so the settle step prunes the row. {@code 400}/{@code 401}/{@code 403}/{@code 413} → a
 * misconfigured key pair, a rejected JWT or an oversized payload — never fixed by a retry, logged
 * at ERROR because the operator has to look. {@code 429}/{@code 5xx}/transport → retryable on the
 * shared attempt budget.
 */
public final class JdkWebPushSender implements WebPushSender {

  private static final Logger log = LoggerFactory.getLogger(JdkWebPushSender.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

  /** How long the push service may hold an undelivered message for an offline device. */
  static final int TTL_SECONDS = 86_400;

  private final HttpClient http;
  private final VapidSigner signer;
  private final String endpointOverride;

  public JdkWebPushSender(WebPushConfig config) {
    this(config, null);
  }

  /**
   * Visible for test: every message is POSTed to {@code endpointOverride} instead of the target's
   * own endpoint, so a local stub can answer. The VAPID audience still comes from the target.
   */
  JdkWebPushSender(WebPushConfig config, String endpointOverride) {
    if (!config.enabled()) {
      throw new IllegalArgumentException("JdkWebPushSender needs a configured VAPID key pair");
    }
    this.signer = new VapidSigner(config.keys(), config.subject());
    this.endpointOverride = endpointOverride;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @Override
  public Integer send(PushTarget target, byte[] payload) {
    byte[] body;
    String authorization;
    try {
      body =
          WebPushEncryptor.encrypt(
              payload, b64(target.p256dh(), "p256dh"), b64(target.auth(), "auth"));
      authorization = signer.authorizationHeader(target.endpoint(), Instant.now());
    } catch (IllegalArgumentException e) {
      // Keys that do not parse, an endpoint with no origin, an oversized payload: a producer or
      // client bug that a retry cannot fix. Not `gone` — the subscription may be fine.
      throw new WebPushException.TerminalWebPushException(
          "cannot build the push message: " + e.getMessage(), false);
    }

    String url = endpointOverride != null ? endpointOverride : target.endpoint();
    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", authorization)
              .header("Content-Encoding", "aes128gcm")
              .header("Content-Type", "application/octet-stream")
              .header("TTL", Integer.toString(TTL_SECONDS))
              .header("Urgency", "normal")
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WebPushException("interrupted while sending", e);
    } catch (Exception e) {
      // Transport fault — retryable, so deliberately NOT terminal.
      throw new WebPushException("could not reach the push service", e);
    }

    int status = response.statusCode();
    if (status == 201 || status == 200) {
      return status;
    }
    String detail = truncate(response.body());
    if (status == 404 || status == 410) {
      throw new WebPushException.TerminalWebPushException(
          "push service " + status + ": subscription is gone", true, status);
    }
    if (status == 429 || status >= 500) {
      throw new WebPushException("push service " + status + ": " + detail, status);
    }
    // 400/401/403/413 and any other 4xx: the message or our identity was refused. A bad VAPID key
    // or an oversized payload is never fixed by a retry — say so loudly.
    log.error(
        "push service {} rejected a message for {} (subscription {}): {}",
        status,
        LoggingWebPushSender.origin(target.endpoint()),
        target.subscriptionId(),
        detail);
    throw new WebPushException.TerminalWebPushException(
        "push service " + status + ": " + detail, false, status);
  }

  private static byte[] b64(String value, String what) {
    try {
      return Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(what + " is not base64url");
    }
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 300 ? s : s.substring(0, 300);
  }
}
