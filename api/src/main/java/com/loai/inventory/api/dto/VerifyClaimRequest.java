package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Body for {@code POST /api/orgs/{orgId}/payment-transactions/{id}/verify} — "Found it" on one
 * shopper claim ({@code stories/payment_claim_verify.md}). Every field is optional and the body may
 * be absent: the claim already carries the reference, the amount and the order. {@code amount} is
 * the bank's figure when it differs from what the shopper claimed ("Different amount").
 */
public class VerifyClaimRequest {

  private BigDecimal amount;
  private OffsetDateTime occurredAt;
  private String verificationProof;

  public VerifyClaimRequest() {}

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(OffsetDateTime occurredAt) {
    this.occurredAt = occurredAt;
  }

  public String getVerificationProof() {
    return verificationProof;
  }

  public void setVerificationProof(String verificationProof) {
    this.verificationProof = verificationProof;
  }
}
