package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A pre-purchase question/comment on a public listing plus its (at most one) official merchant
 * reply (slice R2, {@code stories/storefront_comments.md}). Written on the portal plane by any
 * logged-in customer (no purchase required — epic §2), answered from the staff worklist — which
 * simultaneously publishes the pair — and served anonymously when {@link CommentStatus#ANSWERED}.
 * {@code displayName} is frozen at write; {@code repliedBy} is internal audit and never serializes
 * publicly (the merchant speaks as the store).
 */
public class ListingComment {
  private UUID id;
  private UUID orgId;
  private UUID productListingId;
  private UUID customerId;
  private String body;
  private String displayName;
  private String replyBody;
  private UUID repliedBy;
  private OffsetDateTime repliedAt;
  private CommentStatus status;
  private OffsetDateTime createdAt;

  public ListingComment() {}

  public ListingComment(
      UUID id,
      UUID orgId,
      UUID productListingId,
      UUID customerId,
      String body,
      String displayName,
      String replyBody,
      UUID repliedBy,
      OffsetDateTime repliedAt,
      CommentStatus status,
      OffsetDateTime createdAt) {
    this.id = id;
    this.orgId = orgId;
    this.productListingId = productListingId;
    this.customerId = customerId;
    this.body = body;
    this.displayName = displayName;
    this.replyBody = replyBody;
    this.repliedBy = repliedBy;
    this.repliedAt = repliedAt;
    this.status = status;
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

  public String getReplyBody() {
    return replyBody;
  }

  public void setReplyBody(String replyBody) {
    this.replyBody = replyBody;
  }

  public UUID getRepliedBy() {
    return repliedBy;
  }

  public void setRepliedBy(UUID repliedBy) {
    this.repliedBy = repliedBy;
  }

  public OffsetDateTime getRepliedAt() {
    return repliedAt;
  }

  public void setRepliedAt(OffsetDateTime repliedAt) {
    this.repliedAt = repliedAt;
  }

  public CommentStatus getStatus() {
    return status;
  }

  public void setStatus(CommentStatus status) {
    this.status = status;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public String toString() {
    return "ListingComment{id="
        + id
        + ", orgId="
        + orgId
        + ", productListingId="
        + productListingId
        + ", customerId="
        + customerId
        + ", status="
        + status
        + "}";
  }
}
