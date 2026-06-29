package com.loai.inventory.domain.model;

public enum StockReason {
  RESERVED("Stock Reserved", true),
  RELEASED("Reservation Released", true),
  SOLD("Sale Fulfilled", true),
  RESTOCK("Stock Replenishment", false),
  ADJUSTMENT("Manual Adjustment", false),
  RETURNED("Customer Return", true),
  RESTOCKED_FAILED_FULFILLMENT("Restocked — Failed Fulfillment", true);

  private final String displayName;
  private final boolean requiresOrderId;

  StockReason(String displayName, boolean requiresOrderId) {
    this.displayName = displayName;
    this.requiresOrderId = requiresOrderId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public boolean requiresOrderId() {
    return requiresOrderId;
  }
}
