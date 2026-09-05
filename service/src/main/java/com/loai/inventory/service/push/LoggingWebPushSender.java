package com.loai.inventory.service.push;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev/CI fallback: logs what would have been sent and reports success, so the push pipeline runs
 * end to end with no VAPID key pair. Mirrors {@code LoggingWhatsAppSender}.
 *
 * <p>Logs the endpoint's <b>origin only</b>: the full URL is a bearer capability to message that
 * device, and a dev log is still a log.
 */
public final class LoggingWebPushSender implements WebPushSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingWebPushSender.class);

  @Override
  public Integer send(PushTarget target, byte[] payload) {
    log.info(
        "[push:dev] to={} subscription={} payload={}",
        origin(target.endpoint()),
        target.subscriptionId(),
        new String(payload, StandardCharsets.UTF_8));
    return null; // no service answered — the provider_status column honestly stays null
  }

  static String origin(String endpoint) {
    try {
      return com.loai.inventory.common.security.VapidSigner.originOf(endpoint);
    } catch (RuntimeException e) {
      return "<invalid endpoint>";
    }
  }
}
