package com.loai.inventory.common.exception;

/**
 * 429 — the caller has spent a per-subject attempt budget (today: repeated failed logins against
 * one account). Distinct from the per-IP {@code RateLimitFilter} 429, which never reaches a service
 * at all; this one is raised by the service that knows whose budget was spent.
 *
 * <p>Because that service also knows <em>how long</em> the budget stays spent, the exception
 * carries it: the servlet layer turns {@link #getRetryAfterSeconds()} into a {@code Retry-After}
 * response header. Without it a client can only say "too many attempts" and leave the person
 * guessing whether to try again in a second or a quarter of an hour.
 */
public class TooManyAttemptsException extends AppException {

  private final long retryAfterSeconds;

  public TooManyAttemptsException(String message, long retryAfterSeconds) {
    super(429, message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** Seconds until the budget resets. Always ≥ 1 — a {@code Retry-After: 0} invites a hot loop. */
  public long getRetryAfterSeconds() {
    return Math.max(1, retryAfterSeconds);
  }
}
