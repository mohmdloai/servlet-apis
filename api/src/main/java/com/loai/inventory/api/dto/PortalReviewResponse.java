package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ListingReview;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The customer's own review on the portal plane (slice R1): the row id (the delete handle), the
 * listing's public identity (slug + title), the content and the moderation status — PENDING is
 * visible only to its author here, never publicly.
 */
public class PortalReviewResponse {

  private UUID id;
  private String listingSlug;
  private String listingTitle;
  private int rating;
  private String body;
  private String status;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private PortalReviewResponse() {}

  public static PortalReviewResponse from(
      ListingReview review, String listingSlug, String listingTitle) {
    PortalReviewResponse r = new PortalReviewResponse();
    r.id = review.getId();
    r.listingSlug = listingSlug;
    r.listingTitle = listingTitle;
    r.rating = review.getRating();
    r.body = review.getBody();
    r.status = review.getStatus().name();
    r.createdAt = review.getCreatedAt();
    r.updatedAt = review.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getListingSlug() {
    return listingSlug;
  }

  public String getListingTitle() {
    return listingTitle;
  }

  public int getRating() {
    return rating;
  }

  public String getBody() {
    return body;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
