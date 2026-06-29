package com.loai.inventory.api.dto;

/**
 * Request body for {@code POST /api/orgs/{orgId}/fulfillments/{id}/replace}. The body is optional;
 * {@code carrier} / {@code tracking_number} / {@code notes} seed the replacement fulfillment (all
 * optional, as on a normal create).
 */
public class ReplaceFulfillmentRequest {

  private String carrier;
  private String trackingNumber;
  private String notes;

  public ReplaceFulfillmentRequest() {}

  public String getCarrier() {
    return carrier;
  }

  public void setCarrier(String carrier) {
    this.carrier = carrier;
  }

  public String getTrackingNumber() {
    return trackingNumber;
  }

  public void setTrackingNumber(String trackingNumber) {
    this.trackingNumber = trackingNumber;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
