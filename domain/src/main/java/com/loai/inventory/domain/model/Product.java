package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class Product {
  private UUID id;
  private UUID orgId;
  private String name;
  private String description;
  private BigDecimal basePrice;

  /**
   * What a unit cost the merchant (V92). {@code null} means "not costed" — a different fact from
   * {@code 0.00} — and every consumer treats it that way (no cost-derived figure is ever computed
   * from a null cost, and none is reported as zero in its place).
   */
  private BigDecimal costPrice;

  /**
   * Reorder point (V94): notify staff when available stock reaches or falls below it; null = no
   * rule.
   */
  private Integer reorderPoint;

  private String sku;
  private String barcode;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Product() {}

  public Product(
      UUID id,
      UUID orgId,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.name = name;
    this.description = description;
    this.basePrice = basePrice;
    this.sku = sku;
    this.barcode = barcode;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getBasePrice() {
    return basePrice;
  }

  public void setBasePrice(BigDecimal basePrice) {
    this.basePrice = basePrice;
  }

  public BigDecimal getCostPrice() {
    return costPrice;
  }

  public void setCostPrice(BigDecimal costPrice) {
    this.costPrice = costPrice;
  }

  public Integer getReorderPoint() {
    return reorderPoint;
  }

  public void setReorderPoint(Integer reorderPoint) {
    this.reorderPoint = reorderPoint;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public String getBarcode() {
    return barcode;
  }

  public void setBarcode(String barcode) {
    this.barcode = barcode;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public String toString() {
    return "Product{id=" + id + ", orgId=" + orgId + ", sku='" + sku + "', name='" + name + "'}";
  }
}
