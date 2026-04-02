package com.loai.inventory.api.dto;

import java.util.UUID;

public class InitInventoryRequest {

  private UUID productId;
  private int stockQty;

  public InitInventoryRequest() {}

  public UUID getProductId() {
    return productId;
  }

  public void setProductId(UUID productId) {
    this.productId = productId;
  }

  public int getStockQty() {
    return stockQty;
  }

  public void setStockQty(int stockQty) {
    this.stockQty = stockQty;
  }
}
