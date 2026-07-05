package com.loai.inventory.api.dto;

/**
 * Body for {@code PATCH /product-listings/{id}/images/{imageId}} — edit an attached image's {@code
 * alt_text} and {@code sort_order} (a full replace of both; {@code sort_order} is required).
 */
public class UpdateImageRequest {
  private String altText;
  private Integer sortOrder;

  public UpdateImageRequest() {}

  public String getAltText() {
    return altText;
  }

  public void setAltText(String altText) {
    this.altText = altText;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
  }
}
