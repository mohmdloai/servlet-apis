package com.loai.inventory.common.exception;

import java.math.BigDecimal;

/**
 * 403 — a money-out action is above the org's approval threshold and needs a higher role, today
 * always OWNER. Structurally a plain {@link AuthorizationException} (same status, same message
 * shape), but it carries the three facts a client needs to say <em>why</em>.
 *
 * <p>Without them the refusal is indistinguishable from an ordinary permission denial, and the
 * admin app rendered it as one: "You don't have permission to do that." That is not merely vague,
 * it is wrong — the caller <em>does</em> have permission, the payout just needs a second signature.
 * The numbers existed only inside the human-readable message, which no client renders (raw backend
 * messages never reach a user), so they could not be shown at all. See D12 in {@code
 * docs/defect-remediation-plan.md}.
 *
 * <p>Amounts are decimal EGP, the unit {@code org.refund_approval_threshold} is stored in and the
 * unit every money field on this API already speaks.
 */
public class ApprovalRequiredException extends AuthorizationException {

  private final String requiredRole;
  private final BigDecimal thresholdAmount;
  private final BigDecimal requestedAmount;

  public ApprovalRequiredException(
      String message, String requiredRole, BigDecimal thresholdAmount, BigDecimal requestedAmount) {
    super(message);
    this.requiredRole = requiredRole;
    this.thresholdAmount = thresholdAmount;
    this.requestedAmount = requestedAmount;
  }

  /** The role that could approve this — "OWNER" today; a field so it can be shown, not assumed. */
  public String getRequiredRole() {
    return requiredRole;
  }

  /** The org's configured ceiling for unattended payout per money source. */
  public BigDecimal getThresholdAmount() {
    return thresholdAmount;
  }

  /**
   * What was actually measured against the ceiling — since D1 this is the money source's <b>running
   * total</b>, not the amount of this one call. That distinction is the whole reason this needs to
   * be on the wire: a client comparing only the amount in front of the user cannot derive it, and
   * will show no warning right up until the refusal.
   */
  public BigDecimal getRequestedAmount() {
    return requestedAmount;
  }
}
