package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.CategoryRef;

/** A category chip/breadcrumb on a public listing — name + public slug only. */
public class PublicCategoryRefResponse {
  private String name;
  private String slug;

  private PublicCategoryRefResponse() {}

  public static PublicCategoryRefResponse from(CategoryRef c) {
    PublicCategoryRefResponse r = new PublicCategoryRefResponse();
    r.name = c.name();
    r.slug = c.slug();
    return r;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }
}
