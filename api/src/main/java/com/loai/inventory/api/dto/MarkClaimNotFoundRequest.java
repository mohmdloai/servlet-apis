package com.loai.inventory.api.dto;

/**
 * Body for {@code POST /api/orgs/{orgId}/payment-transactions/{id}/not-found} — "Can't find it" on
 * one shopper claim ({@code stories/payment_claim_not_found.md}). {@code reason} is one of {@code
 * NO_TRANSFER}, {@code DIFFERENT_ACCOUNT}, {@code OTHER}; {@code note} is an optional line for the
 * shopper, quoted to them verbatim.
 */
public class MarkClaimNotFoundRequest {

  private String reason;
  private String note;

  public MarkClaimNotFoundRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }
}
