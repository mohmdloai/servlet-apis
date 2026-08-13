package com.loai.inventory.api.dto;

import com.loai.inventory.domain.repository.StorefrontCrawlRepository;
import java.time.OffsetDateTime;

/**
 * One row of the public store index ({@code GET /api/public/storefronts}) — the read a sitemap
 * index is built from ({@code stories/storefront_crawl_feeds.md}).
 *
 * <p>Deliberately just two fields. {@code catalogUpdatedAt} is {@code MAX(updated_at)} over the
 * store's PUBLISHED listings and exists so the sitemap index can carry a per-store {@code
 * <lastmod>}, which is what tells a crawler which store sitemaps to re-fetch. Carries <b>no</b> id,
 * name, owner, member count, status or listing count — the store's own public profile is one
 * request away for anything else, and a directory row is not the place to grow a second profile.
 */
public record PublicStorefrontRefResponse(String slug, OffsetDateTime catalogUpdatedAt) {

  public static PublicStorefrontRefResponse from(StorefrontCrawlRepository.StoreRef ref) {
    return new PublicStorefrontRefResponse(ref.slug(), ref.catalogUpdatedAt());
  }
}
