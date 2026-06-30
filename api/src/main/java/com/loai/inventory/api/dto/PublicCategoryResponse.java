package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.CategoryNav;

/** A node in the public category nav. Exposes slugs only — no internal UUIDs. */
public class PublicCategoryResponse {
  private String name;
  private String slug;
  private String parentSlug;

  private PublicCategoryResponse() {}

  public static PublicCategoryResponse from(CategoryNav c) {
    PublicCategoryResponse r = new PublicCategoryResponse();
    r.name = c.name();
    r.slug = c.slug();
    r.parentSlug = c.parentSlug();
    return r;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }

  public String getParentSlug() {
    return parentSlug;
  }
}
