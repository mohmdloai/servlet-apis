package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.PublicCollectionView;

/**
 * A row of the public collections rail (roadmap item 8). Exactly {@code {slug, name}} — the slug is
 * the only handle a shopper needs (it addresses the landing page and the {@code ?collection=}
 * predicate), and the name is already locale-resolved server-side. No internal id, no sort order,
 * no membership size: how many things are on a shelf is the merchant's business.
 */
public class PublicCollectionResponse {
  private String slug;
  private String name;

  private PublicCollectionResponse() {}

  public static PublicCollectionResponse from(PublicCollectionView c) {
    PublicCollectionResponse r = new PublicCollectionResponse();
    r.slug = c.slug();
    r.name = c.name();
    return r;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }
}
