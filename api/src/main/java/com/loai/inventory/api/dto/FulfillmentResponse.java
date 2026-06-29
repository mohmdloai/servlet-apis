package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentLine;
import com.loai.inventory.domain.model.FulfillmentStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class FulfillmentResponse {
  private UUID id;
  private UUID orgId;
  private UUID salesOrderId;
  private FulfillmentStatus status;
  private String carrier;
  private String trackingNumber;
  private String notes;
  private OffsetDateTime shippedAt;
  private OffsetDateTime failedAt;
  private String failedReason;
  private OffsetDateTime returnedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private List<FulfillmentLineResponse> lines;

  private FulfillmentResponse() {}

  public static FulfillmentResponse from(Fulfillment fulfillment, List<FulfillmentLine> lines) {
    FulfillmentResponse r = new FulfillmentResponse();
    r.id = fulfillment.getId();
    r.orgId = fulfillment.getOrgId();
    r.salesOrderId = fulfillment.getSalesOrderId();
    r.status = fulfillment.getStatus();
    r.carrier = fulfillment.getCarrier();
    r.trackingNumber = fulfillment.getTrackingNumber();
    r.notes = fulfillment.getNotes();
    r.shippedAt = fulfillment.getShippedAt();
    r.failedAt = fulfillment.getFailedAt();
    r.failedReason = fulfillment.getFailedReason();
    r.returnedAt = fulfillment.getReturnedAt();
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

  public OffsetDateTime getFailedAt() {
    return failedAt;
  }

  public String getFailedReason() {
    return failedReason;
  }

  public OffsetDateTime getReturnedAt() {
    return returnedAt;
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
