package com.loai.inventory.service.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the application's {@link WebPushSender}. Mirrors {@code WhatsAppSenderFactory}: with no
 * VAPID key pair the app still boots and the whole pipeline still runs, against {@link
 * LoggingWebPushSender} — and {@code GET /api/me/push/config} reports {@code enabled:false}, so no
 * browser ever subscribes to a channel that cannot send.
 */
public final class WebPushSenderFactory {

  private static final Logger log = LoggerFactory.getLogger(WebPushSenderFactory.class);

  private WebPushSenderFactory() {}

  public static WebPushSender build(WebPushConfig config) {
    if (config == null || !config.enabled()) {
      log.warn(
          "WEB_PUSH_VAPID_PUBLIC_KEY / WEB_PUSH_VAPID_PRIVATE_KEY not set — using"
              + " LoggingWebPushSender (no real push is sent; the client sees enabled:false)");
      return new LoggingWebPushSender();
    }
    log.info("Initialising Web Push sender (VAPID subject {})", config.subject());
    return new JdkWebPushSender(config);
  }
}
