package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentLine;
import com.loai.inventory.domain.model.FulfillmentResolution;
import com.loai.inventory.domain.model.FulfillmentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class FulfillmentResponse {
  private UUID id;
  private UUID orgId;
  private UUID salesOrderId;
  private String salesOrderNumber;
  private BigDecimal fulfillmentValue;
  private FulfillmentStatus status;
  private String carrier;
  private String trackingNumber;
  private String notes;
  private OffsetDateTime shippedAt;
  private OffsetDateTime deliveredAt;
  private OffsetDateTime cancelledAt;
  private OffsetDateTime failedAt;
  private String failedReason;
  private OffsetDateTime returnedAt;
  private FulfillmentResolution resolution;
  private UUID replacesFulfillmentId;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private List<FulfillmentLineResponse> lines;

  private FulfillmentResponse() {}

  public static FulfillmentResponse from(Fulfillment fulfillment, List<FulfillmentLine> lines) {
    return from(fulfillment, lines, null, null);
  }

  /**
   * Read-path variant: additionally carries the parent order's number (all reads) and the
   * fulfillment's monetary value (detail + by-order reads — the amount a failed-fulfillment refund
   * would move, for client-side threshold pre-warnings). Both null → omitted on mutation responses.
   */
  public static FulfillmentResponse from(
      Fulfillment fulfillment,
      List<FulfillmentLine> lines,
      String salesOrderNumber,
      BigDecimal fulfillmentValue) {
    FulfillmentResponse r = new FulfillmentResponse();
    r.id = fulfillment.getId();
    r.orgId = fulfillment.getOrgId();
    r.salesOrderId = fulfillment.getSalesOrderId();
    r.salesOrderNumber = salesOrderNumber;
    r.fulfillmentValue = fulfillmentValue;
    r.status = fulfillment.getStatus();
    r.carrier = fulfillment.getCarrier();
    r.trackingNumber = fulfillment.getTrackingNumber();
    r.notes = fulfillment.getNotes();
    r.shippedAt = fulfillment.getShippedAt();
    r.deliveredAt = fulfillment.getDeliveredAt();
    r.cancelledAt = fulfillment.getCancelledAt();
    r.failedAt = fulfillment.getFailedAt();
    r.failedReason = fulfillment.getFailedReason();
    r.returnedAt = fulfillment.getReturnedAt();
    r.resolution = fulfillment.getResolution();
    r.replacesFulfillmentId = fulfillment.getReplacesFulfillmentId();
    r.createdAt = fulfillment.getCreatedAt();
    r.updatedAt = fulfillment.getUpdatedAt();
    r.lines = lines.stream().map(FulfillmentLineResponse::from).toList();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public String getSalesOrderNumber() {
    return salesOrderNumber;
  }

  public BigDecimal getFulfillmentValue() {
    return fulfillmentValue;
  }

  public FulfillmentStatus getStatus() {
    return status;
  }

  public String getCarrier() {
    return carrier;
  }

  public String getTrackingNumber() {
    return trackingNumber;
  }

  public String getNotes() {
    return notes;
  }

  public OffsetDateTime getShippedAt() {
    return shippedAt;
  }

  public OffsetDateTime getDeliveredAt() {
    return deliveredAt;
  }

  public OffsetDateTime getCancelledAt() {
    return cancelledAt;
  }

  public OffsetDateTime getFailedAt() {
    return failedAt;
  }

  public String getFailedReason() {
    return failedReason;
  }

  public OffsetDateTime getReturnedAt() {
    return returnedAt;
  }

  public FulfillmentResolution getResolution() {
    return resolution;
  }

  public UUID getReplacesFulfillmentId() {
    return replacesFulfillmentId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public List<FulfillmentLineResponse> getLines() {
    return lines;
  }
}
