package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /inventory/{productId}/adjust} ({@code stories/stocktake_count.md}). A
 * sibling of the shared {@link InventoryQtyRequest} rather than a field on it, so the other four
 * actions read nothing new. {@code reason} is optional — absent means {@code ADJUSTMENT}, exactly
 * what the action always wrote; the handler parses it against the pair the service allows.
 */
public class InventoryAdjustRequest {

  private int qty;
  private String reason;

  public InventoryAdjustRequest() {}

  public int getQty() {
    return qty;
  }

  public void setQty(int qty) {
    this.qty = qty;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
