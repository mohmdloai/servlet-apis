package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class CreateProductListingRequest {
  private UUID productId;
  private String title;
  private String marketingCopy;
  private String slug;
  private BigDecimal salesPrice;
  // Content-localization L2: per-language copy. When absent, the single title/marketingCopy above
  // are used as the org's default-locale row (backward-compatible).
  private List<ProductListingTranslationDto> translations;

  public CreateProductListingRequest() {}

  public List<ProductListingTranslationDto> getTranslations() {
    return translations;
  }

  public void setTranslations(List<ProductListingTranslationDto> translations) {
    this.translations = translations;
  }

  public UUID getProductId() {
    return productId;
  }

  public void setProductId(UUID productId) {
    this.productId = productId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getMarketingCopy() {
    return marketingCopy;
  }

  public void setMarketingCopy(String marketingCopy) {
    this.marketingCopy = marketingCopy;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public BigDecimal getSalesPrice() {
    return salesPrice;
  }

  public void setSalesPrice(BigDecimal salesPrice) {
    this.salesPrice = salesPrice;
  }
}
