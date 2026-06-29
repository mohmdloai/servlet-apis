package com.loai.inventory.api.dto;

/**
 * Request body for {@code POST /api/orgs/{orgId}/fulfillments/{id}/fail}. The body is optional;
 * {@code reason} records why the shipment failed (lost, refused, returned to sender).
 */
public class FailFulfillmentRequest {

  private String reason;

  public FailFulfillmentRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
