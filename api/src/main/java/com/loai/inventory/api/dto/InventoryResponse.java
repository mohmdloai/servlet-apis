package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Inventory;
import java.time.OffsetDateTime;
import java.util.UUID;

public class InventoryResponse {

  private UUID orgId;
  private UUID productId;
  private int stockQty;
  private int reservedQty;
  private int availableQty;
  private long version;
  private OffsetDateTime updatedAt;

  private InventoryResponse() {}

  public static InventoryResponse from(Inventory inv) {
    InventoryResponse r = new InventoryResponse();
    r.orgId = inv.getOrgId();
    r.productId = inv.getProductId();
    r.stockQty = inv.getStockQty();
    r.reservedQty = inv.getReservedQty();
    r.availableQty = inv.getAvailableQty();
    r.version = inv.getVersion();
    r.updatedAt = inv.getUpdatedAt();
    return r;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getProductId() {
    return productId;
  }

  public int getStockQty() {
    return stockQty;
  }

  public int getReservedQty() {
    return reservedQty;
  }

  public int getAvailableQty() {
    return availableQty;
  }

  public long getVersion() {
    return version;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
