package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ListingReview;
import java.time.OffsetDateTime;

/**
 * One public review row (slice R1, epic §6): exactly {@code display_name} (frozen at write), {@code
 * rating}, the optional {@code body} and {@code created_at} — never a customer id, email, internal
 * id or status. APPROVED-only by the service.
 */
public class PublicReviewResponse {

  private String displayName;
  private int rating;
  private String body;
  private OffsetDateTime createdAt;

  private PublicReviewResponse() {}

  public static PublicReviewResponse from(ListingReview review) {
    PublicReviewResponse r = new PublicReviewResponse();
    r.displayName = review.getDisplayName();
    r.rating = review.getRating();
    r.body = review.getBody();
    r.createdAt = review.getCreatedAt();
    return r;
  }

  public String getDisplayName() {
    return displayName;
  }

  public int getRating() {
    return rating;
  }

  public String getBody() {
    return body;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
