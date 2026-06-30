package com.loai.inventory.api.dto;

import java.math.BigDecimal;

public class UpdateProductListingRequest {
  private String title;
  private String marketingCopy;
  private String slug;
  private BigDecimal salesPrice;

  public UpdateProductListingRequest() {}

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
