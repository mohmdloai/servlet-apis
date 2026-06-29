package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.service.FulfillmentService.FailedRefundResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response for refunding a FAILED fulfillment: the fulfillment plus the PENDING refund(s) created.
 * The refunds still need executing separately (the admin performs the real reverse transfer, then
 * {@code POST /refunds/{id}/execute}) — no money has moved yet.
 */
public class FailedFulfillmentRefundResponse {

  private UUID fulfillmentId;
  private UUID salesOrderId;
  private String status;
  private OffsetDateTime failedAt;
  private BigDecimal pendingRefundTotal;
  private List<UUID> pendingRefundIds;

  private FailedFulfillmentRefundResponse() {}

  public static FailedFulfillmentRefundResponse from(FailedRefundResult result) {
    Fulfillment f = result.fulfillment();
    FailedFulfillmentRefundResponse r = new FailedFulfillmentRefundResponse();
    r.fulfillmentId = f.getId();
    r.salesOrderId = f.getSalesOrderId();
    r.status = f.getStatus().name();
    r.failedAt = f.getFailedAt();
    r.pendingRefundTotal = result.pendingRefundTotal();
    r.pendingRefundIds = result.refunds().stream().map(Refund::getId).toList();
    return r;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getFailedAt() {
    return failedAt;
  }

  public BigDecimal getPendingRefundTotal() {
    return pendingRefundTotal;
  }

  public List<UUID> getPendingRefundIds() {
    return pendingRefundIds;
  }
}
