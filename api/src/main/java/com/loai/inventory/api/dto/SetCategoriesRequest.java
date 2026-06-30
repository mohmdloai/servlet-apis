package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/** Body for {@code PUT /product-listings/{id}/categories} — replaces the whole category set. */
public class SetCategoriesRequest {
  private List<UUID> categoryIds;

  public SetCategoriesRequest() {}

  public List<UUID> getCategoryIds() {
    return categoryIds;
  }

  public void setCategoryIds(List<UUID> categoryIds) {
    this.categoryIds = categoryIds;
  }
}
