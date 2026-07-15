package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/portal/comments} (slice R2): the listing's public slug + the question
 * (1..1000 chars, stored verbatim). No purchase required — login is the gate.
 */
public class PortalCommentRequest {

  private String listingSlug;
  private String body;

  private PortalCommentRequest() {}

  public String getListingSlug() {
    return listingSlug;
  }

  public String getBody() {
    return body;
  }
}
