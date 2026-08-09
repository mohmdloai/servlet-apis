package com.loai.inventory.common.exception;

/**
 * 429 — the caller has spent a per-subject attempt budget (today: repeated failed logins against
 * one account). Distinct from the per-IP {@code RateLimitFilter} 429, which never reaches a service
 * at all; this one is raised by the service that knows whose budget was spent.
 */
public class TooManyAttemptsException extends AppException {
  public TooManyAttemptsException(String message) {
    super(429, message);
  }
}
