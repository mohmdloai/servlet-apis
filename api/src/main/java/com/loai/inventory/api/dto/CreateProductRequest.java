package com.loai.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Request body for POST /api/products
 *
 * <p>Jackson deserializes the JSON body into this class. Validation happens in ProductService, not
 * here — this is just a data carrier.
 */
public class CreateProductRequest {

  private String name;
  private String description;
  private BigDecimal basePrice;
  private String sku;
  private String barcode;
  // Tri-state cost (stories/product_cost_and_margin.md): the setter records that the key was on
  // the wire at all, because Jackson hands a plain field the same null for "absent" and for
  // "explicitly null" — and here those are two different instructions (leave alone vs. clear).
  private BigDecimal costPrice;
  private boolean costPricePresent;

  /** Reorder point (V94): null = no rule; full-replace on PUT like {@code barcode}. */
  private Integer reorderPoint;

  public CreateProductRequest() {}

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

  public void setName(String name) {
    this.name = name;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setBasePrice(BigDecimal basePrice) {
    this.basePrice = basePrice;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public void setBarcode(String barcode) {
    this.barcode = barcode;
  }

  public BigDecimal getCostPrice() {
    return costPrice;
  }

  /** True iff {@code cost_price} appeared in the body (with a value or as {@code null}). */
  public boolean isCostPricePresent() {
    return costPricePresent;
  }

  @JsonProperty("cost_price")
  public void setCostPrice(BigDecimal costPrice) {
    this.costPrice = costPrice;
    this.costPricePresent = true;
  }

  public Integer getReorderPoint() {
    return reorderPoint;
  }

  public void setReorderPoint(Integer reorderPoint) {
    this.reorderPoint = reorderPoint;
  }
}
