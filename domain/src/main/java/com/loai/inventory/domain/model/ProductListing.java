package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The public, storefront-facing version of a {@link Product}. Deliberately decoupled from {@code
 * Product} — carries its own {@code salesPrice}, marketing copy, images and lifecycle so internal
 * data never leaks to customers. One listing per product per org.
 */
public class ProductListing {
  private UUID id;
  private UUID orgId;
  private UUID productId;
  private String title;
  private String marketingCopy;
  private String slug;
  private BigDecimal salesPrice;
  private ListingStatus status;
  private OffsetDateTime publishedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public ProductListing() {}

  public ProductListing(
      UUID id,
      UUID orgId,
      UUID productId,
      String title,
      String marketingCopy,
      String slug,
      BigDecimal salesPrice,
      ListingStatus status,
      OffsetDateTime publishedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.productId = productId;
    this.title = title;
    this.marketingCopy = marketingCopy;
    this.slug = slug;
    this.salesPrice = salesPrice;
    this.status = status;
    this.publishedAt = publishedAt;
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

  public ListingStatus getStatus() {
    return status;
  }

  public void setStatus(ListingStatus status) {
    this.status = status;
  }

  public OffsetDateTime getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(OffsetDateTime publishedAt) {
    this.publishedAt = publishedAt;
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
    return "ProductListing{id=" + id + ", orgId=" + orgId + ", status=" + status + "}";
  }
}
