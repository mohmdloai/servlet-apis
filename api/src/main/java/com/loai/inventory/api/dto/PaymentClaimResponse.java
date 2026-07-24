package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.PaymentTransaction;
import java.math.BigDecimal;

/**
 * Customer-safe result of a payment-proof claim (roadmap item 2): confirmation that the claim was
 * recorded (or already on file), plus the {@code amount} + {@code currency} the shopper is on the
 * hook for. Carries no internal transaction id, order id, or reconciliation state — a shopper never
 * sees the staff worklist's internals.
 */
public class PaymentClaimResponse {
  private boolean recorded;
  private String reference;
  private BigDecimal amount;
  private String currency;

  private PaymentClaimResponse() {}

  /** {@code inserted} = this call recorded it (201); false = an idempotent replay (200). */
  public static PaymentClaimResponse from(PaymentTransaction txn, boolean inserted) {
    PaymentClaimResponse r = new PaymentClaimResponse();
    r.recorded = inserted;
    r.reference = txn.getProviderRef();
    r.amount = txn.getAmount();
    r.currency = txn.getCurrency();
    return r;
  }

  public boolean isRecorded() {
    return recorded;
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
}
