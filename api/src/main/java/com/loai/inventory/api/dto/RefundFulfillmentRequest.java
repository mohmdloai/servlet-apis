package com.loai.inventory.api.dto;

/**
 * Request body for {@code POST /api/orgs/{orgId}/fulfillments/{id}/refund}. The body is optional;
 * {@code refund_method} is the channel money goes back through (defaults to the online InstaPay
 * path when absent).
 */
public class RefundFulfillmentRequest {

  private String refundMethod;

  public RefundFulfillmentRequest() {}

  public String getRefundMethod() {
    return refundMethod;
  }

  public void setRefundMethod(String refundMethod) {
    this.refundMethod = refundMethod;
  }
}
