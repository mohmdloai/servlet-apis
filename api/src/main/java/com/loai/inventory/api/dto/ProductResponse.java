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

  /**
   * What a unit cost the merchant (V92). Written only when the caller has manager authority AND the
   * product is costed — below MANAGER the key is absent, never {@code null} or {@code 0}.
   */
  private BigDecimal costPrice;

  /** Reorder point (V94, stories/reorder_point.md); absent when the product has none. */
  private Integer reorderPoint;

  private String sku;
  private String barcode;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private ProductResponse() {}

  /** The pre-V92 shape: no cost crosses. Use this wherever the caller's authority is unknown. */
  public static ProductResponse from(Product p) {
    return from(p, false);
  }

  /**
   * @param costVisible the caller's {@code AuthzHelper.hasManagerAuthority} decision, made once per
   *     request by the handler. Cost is MANAGER-plane data (stories/product_cost_and_margin.md): a
   *     cashier's product list must not carry the shop's markups. There is deliberately no overload
   *     that guesses.
   */
  public static ProductResponse from(Product p, boolean costVisible) {
    ProductResponse r = new ProductResponse();
    r.id = p.getId();
    r.orgId = p.getOrgId();
    r.name = p.getName();
    r.description = p.getDescription();
    r.basePrice = p.getBasePrice();
    r.costPrice = costVisible ? p.getCostPrice() : null;
    r.reorderPoint = p.getReorderPoint();
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

  public BigDecimal getCostPrice() {
    return costPrice;
  }

  public Integer getReorderPoint() {
    return reorderPoint;
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
