package com.loai.inventory.service.push;

/**
 * Hands one encrypted push message to a push service. The {@code WhatsAppSender} shape: an
 * interface, a real implementation ({@link JdkWebPushSender}) and a logging fallback ({@link
 * LoggingWebPushSender}) chosen by {@link WebPushSenderFactory} from env, so the whole pipeline —
 * producer, subtype row, sweeper claim/send/settle — runs in dev and CI with no VAPID key pair.
 *
 * <p>Called only by the delivery sweeper, after the business txn has committed — never inside it.
 */
public interface WebPushSender {

  /**
   * Encrypt {@code payload} for {@code target} and POST it to the target's endpoint.
   *
   * @return the push service's HTTP status (201 or 200), or null when no service was reached (the
   *     logging sender)
   * @throws WebPushException on a transport fault or a retryable status (429, 5xx)
   * @throws WebPushException.TerminalWebPushException on a status a retry cannot fix; {@code gone}
   *     when the subscription itself is dead (404, 410)
   */
  Integer send(PushTarget target, byte[] payload) throws WebPushException;
}
