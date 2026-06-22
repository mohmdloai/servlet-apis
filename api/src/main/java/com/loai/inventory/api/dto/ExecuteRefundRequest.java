package com.loai.inventory.api.dto;

/** Optional body of {@code POST /api/orgs/{orgId}/refunds/{id}/execute}. */
public class ExecuteRefundRequest {
  private String providerRef;

  public ExecuteRefundRequest() {}

  public String getProviderRef() {
    return providerRef;
  }

  public void setProviderRef(String providerRef) {
    this.providerRef = providerRef;
  }
}
