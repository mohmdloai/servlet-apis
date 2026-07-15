package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A verified-purchase review on a public listing (slice R1, {@code stories/storefront_reviews.md}).
 * Written on the portal plane by the session customer (eligibility = a DELIVERED fulfillment line
 * for the listing's product), moderated on the staff plane, served anonymously when {@link
 * ReviewStatus#APPROVED}. {@code displayName} is frozen from {@code customer.name} at write time so
 * the public row never resolves back to the CRM record (epic §6). One review per {@code (customer,
 * listing)} — a resubmission is an edit and resets the status to PENDING.
 */
public class ListingReview {
  private UUID id;
  private UUID orgId;
  private UUID productListingId;
  private UUID customerId;
  private int rating;
  private String body;
  private String displayName;
  private ReviewStatus status;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public ListingReview() {}

  public ListingReview(
      UUID id,
      UUID orgId,
      UUID productListingId,
      UUID customerId,
      int rating,
      String body,
      String displayName,
      ReviewStatus status,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.productListingId = productListingId;
    this.customerId = customerId;
    this.rating = rating;
    this.body = body;
    this.displayName = displayName;
    this.status = status;
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

  public UUID getProductListingId() {
    return productListingId;
  }

  public void setProductListingId(UUID productListingId) {
    this.productListingId = productListingId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public void setCustomerId(UUID customerId) {
    this.customerId = customerId;
  }

  public int getRating() {
    return rating;
  }

  public void setRating(int rating) {
    this.rating = rating;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public ReviewStatus getStatus() {
    return status;
  }

  public void setStatus(ReviewStatus status) {
    this.status = status;
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
    return "ListingReview{id="
        + id
        + ", orgId="
        + orgId
        + ", productListingId="
        + productListingId
        + ", customerId="
        + customerId
        + ", rating="
        + rating
        + ", status="
        + status
        + "}";
  }
}
