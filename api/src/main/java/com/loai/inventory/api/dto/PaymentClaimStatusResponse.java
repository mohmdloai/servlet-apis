package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.PaymentTransaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The shopper's view of their own claim on the customer-safe order reads ({@code payment_claim},
 * {@code stories/payment_claim_not_found.md}): is the store still looking ({@code PENDING}), did
 * they confirm ({@code CONFIRMED}), or could they not find it ({@code NOT_FOUND}, with the reason
 * and the store's note). Reload-safe — it answers the question the shopper is refreshing the page
 * to ask. Customer-safe: no ids, no internal statuses; an ABANDONED claim is simply absent (the
 * order itself says PAID / EXPIRED / CANCELLED).
 */
public class PaymentClaimStatusResponse {

  private String status;
  private String reference;
  private BigDecimal amount;
  private String currency;
  private OffsetDateTime filedAt;
  private String reason;
  private String note;

  private PaymentClaimStatusResponse() {}

  public static Optional<PaymentClaimStatusResponse> from(PaymentTransaction claim) {
    if (claim == null) {
      return Optional.empty();
    }
    String status =
        switch (claim.getVerificationStatus()) {
          case UNVERIFIED -> "PENDING";
          case VERIFIED -> "CONFIRMED";
          case NOT_FOUND -> "NOT_FOUND";
          case ABANDONED -> null;
        };
    if (status == null) {
      return Optional.empty();
    }
    PaymentClaimStatusResponse r = new PaymentClaimStatusResponse();
    r.status = status;
    r.reference = claim.getProviderRef();
    r.amount = claim.getAmount();
    r.currency = claim.getCurrency();
    r.filedAt = claim.getRecordedAt();
    r.reason = claim.getNotFoundReason();
    r.note = claim.getNotFoundNote();
    return Optional.of(r);
  }

  public String getStatus() {
    return status;
  }

  public String getReference() {
    return reference;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public OffsetDateTime getFiledAt() {
    return filedAt;
  }

  /** {@code NO_TRANSFER} / {@code DIFFERENT_ACCOUNT} / {@code OTHER} — NOT_FOUND only. */
  public String getReason() {
    return reason;
  }

  /** The store's note to the shopper — NOT_FOUND only, omitted when the manager wrote none. */
  public String getNote() {
    return note;
  }
}
