package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/portal/reviews} (slice R1): the listing's public slug, a 1–5 rating and
 * an optional plain-text body (≤ 2000 chars, stored verbatim). A resubmission for the same listing
 * is an edit — the upsert on {@code (customer, listing)}.
 */
public class PortalReviewRequest {

  private String listingSlug;
  private Integer rating;
  private String body;

  private PortalReviewRequest() {}

  public String getListingSlug() {
    return listingSlug;
  }

  public Integer getRating() {
    return rating;
  }

  public String getBody() {
    return body;
  }
}
