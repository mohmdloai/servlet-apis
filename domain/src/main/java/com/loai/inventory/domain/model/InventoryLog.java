package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class InventoryLog {
  private Long id;
  private UUID productId;
  private int stockDelta;
  private int reservedDelta;
  private int stockAfter;
  private int reservedAfter;
  private StockReason reason;
  private UUID orderId;
  private String actorId;
  private ActorType actorType;
  private OffsetDateTime createdAt;

  public InventoryLog() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public UUID getProductId() {
    return productId;
  }

  public void setProductId(UUID productId) {
    this.productId = productId;
  }

  public int getStockDelta() {
    return stockDelta;
  }

  public void setStockDelta(int stockDelta) {
    this.stockDelta = stockDelta;
  }

  public int getReservedDelta() {
    return reservedDelta;
  }

  public void setReservedDelta(int reservedDelta) {
    this.reservedDelta = reservedDelta;
  }

  public int getStockAfter() {
    return stockAfter;
  }

  public void setStockAfter(int stockAfter) {
    this.stockAfter = stockAfter;
  }

  public int getReservedAfter() {
    return reservedAfter;
  }

  public void setReservedAfter(int reservedAfter) {
    this.reservedAfter = reservedAfter;
  }

  public StockReason getReason() {
    return reason;
  }

  public void setReason(StockReason reason) {
    this.reason = reason;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public void setOrderId(UUID orderId) {
    this.orderId = orderId;
  }

  public String getActorId() {
    return actorId;
  }

  public void setActorId(String actorId) {
    this.actorId = actorId;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public void setActorType(ActorType actorType) {
    this.actorType = actorType;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public String toString() {
    return "InventoryLog{id="
        + id
        + ", productId="
        + productId
        + ", stockDelta="
        + stockDelta
        + ", reservedDelta="
        + reservedDelta
        + ", stockAfter="
        + stockAfter
        + ", reservedAfter="
        + reservedAfter
        + ", reason="
        + reason
        + ", actorId="
        + actorId
        + ", actorType="
        + actorType
        + "}";
  }
}
