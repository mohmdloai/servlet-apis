package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class Inventory {
  private UUID orgId;
  private UUID productId;
  private int stockQty;
  private int reservedQty;
  private long version;
  private OffsetDateTime updatedAt;

  public Inventory() {}

  public Inventory(
      UUID orgId,
      UUID productId,
      int stockQty,
      int reservedQty,
      long version,
      OffsetDateTime updatedAt) {
    this.orgId = orgId;
    this.productId = productId;
    this.stockQty = stockQty;
    this.reservedQty = reservedQty;
    this.version = version;
    this.updatedAt = updatedAt;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

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

  public int getReservedQty() {
    return reservedQty;
  }

  public void setReservedQty(int reservedQty) {
    this.reservedQty = reservedQty;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public int getAvailableQty() {
    return stockQty - reservedQty;
  }

  @Override
  public String toString() {
    return "Inventory{orgId="
        + orgId
        + ", productId="
        + productId
        + ", stock="
        + stockQty
        + ", reserved="
        + reservedQty
        + ", available="
        + getAvailableQty()
        + ", version="
        + version
        + "}";
  }
}
