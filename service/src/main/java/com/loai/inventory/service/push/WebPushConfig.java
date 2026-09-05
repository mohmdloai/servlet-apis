package com.loai.inventory.service.push;

import com.loai.inventory.common.security.VapidKeys;

/**
 * The process-wide Web Push configuration: the VAPID identity and the operator contact. {@code
 * enabled} is what {@code GET /api/me/push/config} reports and what gates subscribing — with no key
 * pair the switch honestly reads "unavailable" instead of storing subscriptions nobody can send to.
 *
 * <p>Built by {@link #fromEnv} on the {@code SecretBox} rule: both keys unset → disabled; a key
 * present but malformed → startup failure.
 */
public record WebPushConfig(VapidKeys keys, String subject) {

  /** The default {@code sub} when the operator set none — a valid https: origin is enough. */
  static final String DEFAULT_SUBJECT = "https://yabta3.com";

  public static WebPushConfig disabled() {
    return new WebPushConfig(VapidKeys.disabled(), DEFAULT_SUBJECT);
  }

  /**
   * @param publicKeyB64 {@code WEB_PUSH_VAPID_PUBLIC_KEY}
   * @param privateKeyB64 {@code WEB_PUSH_VAPID_PRIVATE_KEY}
   * @param subject {@code WEB_PUSH_SUBJECT} (optional)
   * @throws IllegalArgumentException when a key is present but malformed (a startup failure)
   */
  public static WebPushConfig fromEnv(String publicKeyB64, String privateKeyB64, String subject) {
    VapidKeys keys = VapidKeys.fromBase64Url(publicKeyB64, privateKeyB64);
    String sub = subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject.strip();
    return new WebPushConfig(keys, sub);
  }

  public boolean enabled() {
    return keys != null && keys.isConfigured();
  }

  /** The base64url public key the browser subscribes with; null when disabled. */
  public String publicKeyBase64Url() {
    return enabled() ? keys.publicKeyBase64Url() : null;
  }
}
