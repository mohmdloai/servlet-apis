package com.loai.inventory.api.dto;

/**
 * Request body for {@code POST /api/orgs/{orgId}/sales-orders/{id}/cancel}. Both fields optional:
 * {@code reason} is free-text; {@code refund_method} is the channel any prepayment is refunded
 * through ({@code instapay_manual} / {@code cash} / {@code instapay_in_store}), defaulting to the
 * online InstaPay path. A null/empty body is valid (cancel with no money to refund).
 */
public class CancelOrderRequest {

  private String reason;
  private String refundMethod;

  public CancelOrderRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getRefundMethod() {
    return refundMethod;
  }

  public void setRefundMethod(String refundMethod) {
    this.refundMethod = refundMethod;
  }
}
