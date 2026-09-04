package com.loai.inventory.domain.model;

public enum StockReason {
  RESERVED("Stock Reserved", true),
  RELEASED("Reservation Released", true),
  SOLD("Sale Fulfilled", true),
  RESTOCK("Stock Replenishment", false),
  ADJUSTMENT("Manual Adjustment", false),
  RETURNED("Customer Return", true),
  RESTOCKED_FAILED_FULFILLMENT("Restocked — Failed Fulfillment", true),
  /**
   * A physical count's variance (V93, {@code stories/stocktake_count.md}). Written only by {@code
   * InventoryService.adjust} when the caller says so; the ledger row is the whole record of the
   * count — there is no stocktake table.
   */
  STOCKTAKE("Stocktake", false);

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
