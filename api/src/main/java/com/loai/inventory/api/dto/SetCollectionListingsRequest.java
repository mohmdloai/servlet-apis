package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Body for {@code PUT /collections/{id}/listings} — set-replaces one collection's ordered
 * membership (roadmap item 8), the {@code PUT /product-listings/featured} semantics scoped to a
 * collection. Array order becomes the curated {@code sort} 0..n-1; a {@code null}/absent list
 * empties the collection. Validation (≤ 100, no duplicates, all org-owned) is the service's job.
 */
public class SetCollectionListingsRequest {
  private List<UUID> listingIds;

  public SetCollectionListingsRequest() {}

  public List<UUID> getListingIds() {
    return listingIds;
  }

  public void setListingIds(List<UUID> listingIds) {
    this.listingIds = listingIds;
  }
}
