package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Body for {@code PUT /api/orgs/{orgId}/storefront/banners/order} — the ordered banner ids whose
 * position becomes the new {@code sort_order}. Must be exactly the org's banner set (the service
 * rejects duplicates / foreign / missing ids with a 400).
 */
public class ReorderBannersRequest {

  private List<UUID> ids;

  public ReorderBannersRequest() {}

  public List<UUID> getIds() {
    return ids;
  }

  public void setIds(List<UUID> ids) {
    this.ids = ids;
  }
}
