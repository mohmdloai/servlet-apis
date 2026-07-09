package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Product;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class ProductResponse {

  private UUID id;
  private UUID orgId;
  private String name;
  private String description;
  private BigDecimal basePrice;
  private String sku;
  private String barcode;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private ProductResponse() {}

  public static ProductResponse from(Product p) {
    ProductResponse r = new ProductResponse();
    r.id = p.getId();
    r.orgId = p.getOrgId();
    r.name = p.getName();
    r.description = p.getDescription();
    r.basePrice = p.getBasePrice();
    r.sku = p.getSku();
    r.barcode = p.getBarcode();
    r.createdAt = p.getCreatedAt();
    r.updatedAt = p.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public BigDecimal getBasePrice() {
    return basePrice;
  }

  public String getSku() {
    return sku;
  }

  public String getBarcode() {
    return barcode;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
