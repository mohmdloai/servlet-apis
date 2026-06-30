package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.service.ProductListingService.ListingView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Listing response. The list endpoint emits the core fields ({@link #from}); the detail endpoint
 * additionally populates {@code categoryIds} and {@code images} ({@link #fromView}). Null fields
 * are omitted by the configured ObjectMapper.
 */
public class ProductListingResponse {
  private UUID id;
  private UUID orgId;
  private UUID productId;
  private String title;
  private String marketingCopy;
  private String slug;
  private BigDecimal salesPrice;
  private String status;
  private OffsetDateTime publishedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private List<UUID> categoryIds;
  private List<ProductListingImageResponse> images;

  private ProductListingResponse() {}

  public static ProductListingResponse from(ProductListing l) {
    ProductListingResponse r = new ProductListingResponse();
    r.id = l.getId();
    r.orgId = l.getOrgId();
    r.productId = l.getProductId();
    r.title = l.getTitle();
    r.marketingCopy = l.getMarketingCopy();
    r.slug = l.getSlug();
    r.salesPrice = l.getSalesPrice();
    r.status = l.getStatus() == null ? null : l.getStatus().name();
    r.publishedAt = l.getPublishedAt();
    r.createdAt = l.getCreatedAt();
    r.updatedAt = l.getUpdatedAt();
    return r;
  }

  public static ProductListingResponse fromView(ListingView view) {
    ProductListingResponse r = from(view.listing());
    r.categoryIds = view.categoryIds();
    r.images = view.images().stream().map(ProductListingImageResponse::from).toList();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getTitle() {
    return title;
  }

  public String getMarketingCopy() {
    return marketingCopy;
  }

  public String getSlug() {
    return slug;
  }

  public BigDecimal getSalesPrice() {
    return salesPrice;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getPublishedAt() {
    return publishedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public List<UUID> getCategoryIds() {
    return categoryIds;
  }

  public List<ProductListingImageResponse> getImages() {
    return images;
  }
}
