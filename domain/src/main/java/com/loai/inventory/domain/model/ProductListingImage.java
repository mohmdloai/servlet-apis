package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An image attached to a {@link ProductListing}. We persist only the object-storage key plus
 * display metadata; the bytes live in S3/MinIO and are served via short-lived presigned URLs.
 */
public class ProductListingImage {
  private UUID id;
  private UUID orgId;
  private UUID listingId;
  private String objectKey;
  private String altText;
  private int sortOrder;
  private OffsetDateTime createdAt;

  public ProductListingImage() {}

  public ProductListingImage(
      UUID id,
      UUID orgId,
      UUID listingId,
      String objectKey,
      String altText,
      int sortOrder,
      OffsetDateTime createdAt) {
    this.id = id;
    this.orgId = orgId;
    this.listingId = listingId;
    this.objectKey = objectKey;
    this.altText = altText;
    this.sortOrder = sortOrder;
    this.createdAt = createdAt;
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

  public UUID getListingId() {
    return listingId;
  }

  public void setListingId(UUID listingId) {
    this.listingId = listingId;
  }

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

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
