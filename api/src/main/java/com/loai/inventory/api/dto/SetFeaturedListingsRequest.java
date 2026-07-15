package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Body for {@code PUT /product-listings/featured} — set-replaces the org's ordered featured list
 * (slice C3). Array order becomes {@code featured_sort} 0..n-1; a {@code null}/absent list clears
 * the whole selection. Validation (≤ 12, no duplicates, all org-owned) is the service's job.
 */
public class SetFeaturedListingsRequest {
  private List<UUID> listingIds;

  public SetFeaturedListingsRequest() {}

  public List<UUID> getListingIds() {
    return listingIds;
  }

  public void setListingIds(List<UUID> listingIds) {
    this.listingIds = listingIds;
  }
}
