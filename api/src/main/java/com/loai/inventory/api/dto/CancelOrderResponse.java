package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response for a successful order cancellation: the cancelled order, reservations released, and the
 * PENDING refund(s) created. The refunds still need to be executed separately (the admin performs
 * the real reverse transfer, then {@code POST /refunds/{id}/execute}) — no money has moved yet.
 */
public class CancelOrderResponse {

  private UUID orderId;
  private String orderNumber;
  private String status;
  private OffsetDateTime cancelledAt;
  private int reservationsReleased;
  private BigDecimal pendingRefundTotal;
  private List<UUID> pendingRefundIds;

  public CancelOrderResponse() {}

  public UUID getOrderId() {
    return orderId;
  }

  public void setOrderId(UUID orderId) {
    this.orderId = orderId;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public void setOrderNumber(String orderNumber) {
    this.orderNumber = orderNumber;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public OffsetDateTime getCancelledAt() {
    return cancelledAt;
  }

  public void setCancelledAt(OffsetDateTime cancelledAt) {
    this.cancelledAt = cancelledAt;
  }

  public int getReservationsReleased() {
    return reservationsReleased;
  }

  public void setReservationsReleased(int reservationsReleased) {
    this.reservationsReleased = reservationsReleased;
  }

  public BigDecimal getPendingRefundTotal() {
    return pendingRefundTotal;
  }

  public void setPendingRefundTotal(BigDecimal pendingRefundTotal) {
    this.pendingRefundTotal = pendingRefundTotal;
  }

  public List<UUID> getPendingRefundIds() {
    return pendingRefundIds;
  }

  public void setPendingRefundIds(List<UUID> pendingRefundIds) {
    this.pendingRefundIds = pendingRefundIds;
  }
}
