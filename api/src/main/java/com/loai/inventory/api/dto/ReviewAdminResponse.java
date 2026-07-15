package com.loai.inventory.api.dto;

import com.loai.inventory.domain.repository.ListingReviewRepository.AdminReview;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One staff moderation-worklist row (slice R1): the review plus the customer context staff already
 * see in the CRM (name/email) and the listing title. Never serialized on the public plane.
 */
public class ReviewAdminResponse {

  private UUID id;
  private String listingTitle;
  private int rating;
  private String body;
  private String displayName;
  private String customerName;
  private String customerEmail;
  private String status;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private ReviewAdminResponse() {}

  public static ReviewAdminResponse from(AdminReview row) {
    ReviewAdminResponse r = new ReviewAdminResponse();
    r.id = row.review().getId();
    r.listingTitle = row.listingTitle();
    r.rating = row.review().getRating();
    r.body = row.review().getBody();
    r.displayName = row.review().getDisplayName();
    r.customerName = row.customerName();
    r.customerEmail = row.customerEmail();
    r.status = row.review().getStatus().name();
    r.createdAt = row.review().getCreatedAt();
    r.updatedAt = row.review().getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
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

  public String getDisplayName() {
    return displayName;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerEmail() {
    return customerEmail;
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
