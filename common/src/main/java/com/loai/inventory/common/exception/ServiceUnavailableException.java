package com.loai.inventory.common.exception;

/**
 * A capability this deployment has not switched on — HTTP <b>503</b>. The request was well-formed
 * and the caller is allowed; what is missing is configuration on this server, so the honest answer
 * is "not here, not now" rather than a 400 (nothing about the request is wrong) or a 500 (nothing
 * is broken).
 *
 * <p>First use: {@code POST /api/me/push-subscriptions} when no VAPID key pair is configured. The
 * client is told the same thing up front by {@code GET /api/me/push/config}'s {@code
 * enabled:false}; this is the backstop for a client that did not ask.
 */
public class ServiceUnavailableException extends AppException {
  public ServiceUnavailableException(String message) {
    super(503, message);
  }
}
