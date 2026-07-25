package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/portal/wishlist} (roadmap item 3): the listing's public slug, and
 * nothing else. The customer is the session's, never a body field, and the listing's internal id
 * never appears on the wire in either direction.
 */
public class PortalWishlistRequest {

  private String listingSlug;

  private PortalWishlistRequest() {}

  public String getListingSlug() {
    return listingSlug;
  }
}
