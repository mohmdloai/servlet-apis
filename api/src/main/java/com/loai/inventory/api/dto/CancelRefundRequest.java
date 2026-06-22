package com.loai.inventory.api.dto;

/** Optional body of {@code POST /api/orgs/{orgId}/refunds/{id}/cancel}. */
public class CancelRefundRequest {
  private String reason;

  public CancelRefundRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
