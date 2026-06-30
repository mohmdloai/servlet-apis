package com.loai.inventory.api.dto;

/** Body for {@code POST /product-listings/{id}/images} — attach an already-uploaded object. */
public class AttachImageRequest {
  private String objectKey;
  private String altText;
  private Integer sortOrder;

  public AttachImageRequest() {}

  public String getObjectKey() {
    return objectKey;
  }

  public void setObjectKey(String objectKey) {
    this.objectKey = objectKey;
  }

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
