package com.loai.inventory.api.dto;

import com.loai.inventory.domain.repository.StorefrontCrawlRepository;
import com.loai.inventory.service.StorefrontService;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One store's indexable URL set ({@code GET /api/public/{orgSlug}/crawl-feed}) — everything a
 * sitemap needs and nothing else ({@code stories/storefront_crawl_feeds.md}).
 *
 * <p><b>Deliberately not the catalog read.</b> Building a sitemap from {@code GET
 * /listings?size=100} costs {@code ceil(N/100)} requests of fully-enriched rows — batch-loaded
 * images, variants, categories, rating aggregates — to extract two fields. It would also mean
 * widening {@link PublicListingResponse}, whose javadoc states timestamps are deliberately omitted
 * and whose whitelist is pinned by a structural test; that guarantee is worth more than the few
 * lines this DTO duplicates.
 *
 * <p>No id, title, price, image, status or locale crosses. Every entry is a slug (or a page {@code
 * kind}) and a timestamp.
 */
public record PublicCrawlFeedResponse(
    List<Entry> listings,
    List<Entry> categories,
    List<Entry> collections,
    List<PageEntry> pages,
    boolean hasFeatured,
    long totalListings,
    boolean truncated) {

  /**
   * One indexable URL. {@code updatedAt} becomes the sitemap's {@code <lastmod>} and is the entity
   * row's own timestamp — <b>never synthesised</b>. A sitemap that stamps everything with "now"
   * teaches a crawler to ignore the field, which is worse than not sending it. Note that the
   * translation tables carry no {@code updated_at}, so a localized-name-only edit does not advance
   * it; that is documented rather than papered over.
   */
  public record Entry(String slug, OffsetDateTime updatedAt) {
    static Entry from(StorefrontCrawlRepository.CrawlEntry e) {
      return new Entry(e.key(), e.updatedAt());
    }
  }

  /**
   * A CMS page. Keyed by {@code kind} — the closed set {@code about}/{@code policies} — and not by
   * a slug, because that is what the C4 read calls it and what the route segment is; a field named
   * {@code slug} carrying {@code "about"} would be a small lie a client eventually trips on.
   */
  public record PageEntry(String kind, OffsetDateTime updatedAt) {
    static PageEntry from(StorefrontCrawlRepository.CrawlEntry e) {
      return new PageEntry(e.key(), e.updatedAt());
    }
  }

  public static PublicCrawlFeedResponse from(StorefrontService.CrawlFeed feed) {
    return new PublicCrawlFeedResponse(
        feed.listings().stream().map(Entry::from).toList(),
        feed.categories().stream().map(Entry::from).toList(),
        feed.collections().stream().map(Entry::from).toList(),
        feed.pages().stream().map(PageEntry::from).toList(),
        feed.hasFeatured(),
        feed.totalListings(),
        feed.truncated());
  }
}
