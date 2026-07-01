package com.loai.inventory.api.dto;

/** Optional body for impersonation start: an audit justification. */
public class ImpersonateRequest {
  private String reason;

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
