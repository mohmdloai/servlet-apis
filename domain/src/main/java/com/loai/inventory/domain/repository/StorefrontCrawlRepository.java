package com.loai.inventory.domain.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The reads behind the anonymous crawl surface ({@code stories/storefront_crawl_feeds.md}) — the
 * store index a sitemap index is built from, and the per-store URL set a store sitemap is built
 * from.
 *
 * <p>A <b>sibling</b> of the catalog repositories rather than methods bolted onto them, under the
 * same three rules the platform console's siblings follow: <b>read-only</b>, <b>whitelisted
 * rows</b> (the two records below are the entire vocabulary — a slug or a kind, and a timestamp),
 * and one purpose. Nothing here returns a title, a price, an image or a status, because a sitemap
 * needs none of those and a DTO that could carry them is a DTO someone will later make carry them.
 *
 * <p><b>Why a separate type at all:</b> reusing the paged catalog read to build a sitemap costs
 * {@code ceil(N/100)} requests of fully-enriched rows — batch-loaded images, variants, categories,
 * rating aggregates — to extract two fields, against a rate-limit bucket shared with real shoppers.
 * It would also mean widening {@code PublicListingResponse}, whose javadoc states timestamps are
 * deliberately omitted and whose whitelist is pinned by a structural test. That guarantee is worth
 * more than the code this type duplicates.
 *
 * <p><b>This is the one place in the codebase besides {@code PlatformStatsRepository} and its
 * siblings whose reads intentionally omit {@code org_id}</b> — {@link #findIndexableStores} is
 * cross-org by definition. It stays safe by being counts-and-slugs only, read-only, and serving a
 * surface that is public on purpose. Do not add un-scoped reads anywhere else.
 */
public interface StorefrontCrawlRepository {

  /**
   * One indexable storefront. {@code catalogUpdatedAt} is {@code MAX(product_listing.updated_at)}
   * over the org's PUBLISHED listings — deliberately the listing maximum and not a {@code GREATEST}
   * across four tables: it is the dominant signal, it is one index-servable aggregate, and the
   * field name says exactly what it measures. It exists so the sitemap <i>index</i> can carry a
   * per-store {@code <lastmod>}, which is what tells a crawler which store sitemaps to re-fetch.
   */
  record StoreRef(String slug, OffsetDateTime catalogUpdatedAt) {}

  /**
   * One indexable URL's worth of facts: its {@code key} (a slug, or a page {@code kind}) and the
   * {@code updatedAt} that becomes its {@code <lastmod>}.
   *
   * <p><b>{@code updatedAt} is the entity row's, and translation tables have none.</b> {@code
   * product_listing_translation} / {@code collection_translation} carry no {@code updated_at}, so
   * an edit that changes only a localized name does not move this timestamp. Stated rather than
   * papered over: the alternative is stamping {@code now()}, which teaches a crawler the field is
   * noise. If translation-cadence {@code lastmod} ever matters it is a column on those tables, not
   * a lie here.
   */
  record CrawlEntry(String key, OffsetDateTime updatedAt) {}

  /**
   * Active orgs holding at least one PUBLISHED listing, {@code slug ASC}, paged. The published
   * predicate is the {@code collections} rail's rule verbatim — an empty shelf is never advertised,
   * and an empty store in a search index is the same lie with a worse audience. A set, not a queue,
   * so a stable alphabetical order is what lets a diff of two fetches mean something.
   */
  List<StoreRef> findIndexableStores(int offset, int limit);

  /** The true count behind {@link #findIndexableStores}, for the page envelope. */
  long countIndexableStores();

  /** PUBLISHED listing slugs + timestamps, {@code slug ASC}, capped by {@code limit}. */
  List<CrawlEntry> publishedListings(UUID orgId, int limit);

  /** The true count of PUBLISHED listings, so a capped feed can report its own truncation. */
  long countPublishedListings(UUID orgId);

  /** Categories holding ≥ 1 PUBLISHED listing (an empty category page is thin content). */
  List<CrawlEntry> nonEmptyCategories(UUID orgId);

  /** Collections holding ≥ 1 PUBLISHED listing — the same rule the public rail already applies. */
  List<CrawlEntry> nonEmptyCollections(UUID orgId);

  /**
   * The CMS pages that exist, keyed by {@code kind} (the closed set {@code about}/{@code
   * policies}).
   */
  List<CrawlEntry> pages(UUID orgId);

  /**
   * Whether any PUBLISHED listing is pinned — the frontend includes {@code /featured} only if so.
   */
  boolean hasFeatured(UUID orgId);
}
