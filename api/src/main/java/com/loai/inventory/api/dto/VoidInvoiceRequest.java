package com.loai.inventory.api.dto;

/** Body of {@code POST /api/orgs/{orgId}/invoices/{id}/void}. */
public class VoidInvoiceRequest {
  private String reason;

  public VoidInvoiceRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
