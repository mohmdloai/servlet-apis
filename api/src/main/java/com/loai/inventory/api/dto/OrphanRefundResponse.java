package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.Refund;

/**
 * Response of {@code POST /api/orgs/{orgId}/payment-transactions/{id}/refund}: the (still ORPHAN)
 * transaction with its newly promoted standalone payment, plus the PENDING direct refund the admin
 * must execute after performing the real reverse transfer.
 */
public class OrphanRefundResponse {

  private PaymentTransactionResponse transaction;
  private RefundResponse refund;

  private OrphanRefundResponse() {}

  public static OrphanRefundResponse from(PaymentTransaction txn, Payment payment, Refund refund) {
    OrphanRefundResponse r = new OrphanRefundResponse();
    r.transaction = PaymentTransactionResponse.from(txn, payment, null);
    r.refund = RefundResponse.from(refund);
    return r;
  }

  public PaymentTransactionResponse getTransaction() {
    return transaction;
  }

  public RefundResponse getRefund() {
    return refund;
  }
}
