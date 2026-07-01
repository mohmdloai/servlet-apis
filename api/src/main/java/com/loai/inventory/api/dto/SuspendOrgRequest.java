package com.loai.inventory.api.dto;

/** Optional body for {@code POST /api/admin/orgs/{orgId}/suspend}: an audit justification. */
public class SuspendOrgRequest {
  private String reason;

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
