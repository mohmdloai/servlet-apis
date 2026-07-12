package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.AvailabilityView;

/**
 * One row of the batch availability response ({@code GET /api/public/{orgSlug}/availability}): a
 * public slug and the boolean {@code in_stock} — never a quantity. A {@code false} is opaque (out
 * of stock, untracked, or not PUBLISHED are indistinguishable). See {@code
 * stories/storefront_availability_signal.md}.
 */
public class PublicAvailabilityResponse {

  private String slug;
  private boolean inStock;

  private PublicAvailabilityResponse() {}

  public static PublicAvailabilityResponse from(AvailabilityView v) {
    PublicAvailabilityResponse r = new PublicAvailabilityResponse();
    r.slug = v.slug();
    r.inStock = v.inStock();
    return r;
  }

  public String getSlug() {
    return slug;
  }

  public boolean isInStock() {
    return inStock;
  }
}
