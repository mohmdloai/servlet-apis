package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/orgs/{orgId}/fulfillments}.
 *
 * <p>{@code lines} selects which of the order's lines to ship in this fulfillment; v1 ships each
 * selected line's full reserved quantity. Validation lives in {@link
 * com.loai.inventory.service.FulfillmentService}; this is a Jackson data carrier (snake_case JSON ↔
 * camelCase Java via the global {@code ObjectMapper}).
 */
public class CreateFulfillmentRequest {

  private UUID salesOrderId;
  private List<LinePayload> lines;
  private String carrier;
  private String trackingNumber;
  private String notes;

  public CreateFulfillmentRequest() {}

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public void setSalesOrderId(UUID salesOrderId) {
    this.salesOrderId = salesOrderId;
  }

  public List<LinePayload> getLines() {
    return lines;
  }

  public void setLines(List<LinePayload> lines) {
    this.lines = lines;
  }

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

  public static class LinePayload {
    private UUID salesOrderLineId;

    public LinePayload() {}

    public UUID getSalesOrderLineId() {
      return salesOrderLineId;
    }

    public void setSalesOrderLineId(UUID salesOrderLineId) {
      this.salesOrderLineId = salesOrderLineId;
    }
  }
}
