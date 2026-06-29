package com.loai.inventory.api.dto;

/** Optional body of {@code POST /api/orgs/{orgId}/payments/{id}/dispute}. */
public class DisputePaymentRequest {
  private String reason;

  public DisputePaymentRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
