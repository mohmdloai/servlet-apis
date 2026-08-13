package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.COLLECTION;
import static com.loai.inventory.repository.generated.Tables.COLLECTION_LISTING;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_CATEGORY;
import static com.loai.inventory.repository.generated.Tables.STOREFRONT_PAGE;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.selectOne;

import com.loai.inventory.domain.repository.StorefrontCrawlRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;

/**
 * The anonymous crawl reads ({@code stories/storefront_crawl_feeds.md}). Read-only, whitelisted to
 * a slug-or-kind plus a timestamp, and — for {@link #findIndexableStores} alone — deliberately
 * cross-org. See {@link StorefrontCrawlRepository} for why this is a sibling type rather than
 * methods on the catalog repositories.
 */
public final class StorefrontCrawlRepositoryImpl implements StorefrontCrawlRepository {

  private static final com.loai.inventory.repository.generated.enums.ListingStatus PUBLISHED =
      com.loai.inventory.repository.generated.enums.ListingStatus.PUBLISHED;

  private final DSLContext dsl;

  public StorefrontCrawlRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<StoreRef> findIndexableStores(int offset, int limit) {
    Field<OffsetDateTime> newest = max(PRODUCT_LISTING.UPDATED_AT);
    return dsl.select(ORG.SLUG, newest)
        .from(ORG)
        .join(PRODUCT_LISTING)
        .on(PRODUCT_LISTING.ORG_ID.eq(ORG.ID).and(PRODUCT_LISTING.STATUS.eq(PUBLISHED)))
        .where(ORG.ACTIVE.isTrue())
        .groupBy(ORG.ID, ORG.SLUG)
        .orderBy(ORG.SLUG.asc())
        .limit(limit)
        .offset(offset)
        .fetch(r -> new StoreRef(r.get(ORG.SLUG), r.get(newest)));
  }

  @Override
  public long countIndexableStores() {
    // COUNT over the grouped set — the same predicate as the row query, so the envelope's `total`
    // and the rows it describes cannot disagree.
    return dsl.fetchCount(
        dsl.selectDistinct(ORG.ID)
            .from(ORG)
            .join(PRODUCT_LISTING)
            .on(PRODUCT_LISTING.ORG_ID.eq(ORG.ID).and(PRODUCT_LISTING.STATUS.eq(PUBLISHED)))
            .where(ORG.ACTIVE.isTrue()));
  }

  @Override
  public List<CrawlEntry> publishedListings(UUID orgId, int limit) {
    return dsl.select(PRODUCT_LISTING.SLUG, PRODUCT_LISTING.UPDATED_AT)
        .from(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.STATUS.eq(PUBLISHED)))
        .orderBy(PRODUCT_LISTING.SLUG.asc())
        .limit(limit)
        .fetch(r -> new CrawlEntry(r.value1(), r.value2()));
  }

  @Override
  public long countPublishedListings(UUID orgId) {
    return dsl.fetchCount(
        PRODUCT_LISTING,
        PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.STATUS.eq(PUBLISHED)));
  }

  @Override
  public List<CrawlEntry> nonEmptyCategories(UUID orgId) {
    return dsl.select(CATEGORY.SLUG, CATEGORY.UPDATED_AT)
        .from(CATEGORY)
        .where(
            CATEGORY
                .ORG_ID
                .eq(orgId)
                .andExists(
                    selectOne()
                        .from(PRODUCT_LISTING_CATEGORY)
                        .join(PRODUCT_LISTING)
                        .on(PRODUCT_LISTING.ID.eq(PRODUCT_LISTING_CATEGORY.LISTING_ID))
                        .where(
                            PRODUCT_LISTING_CATEGORY
                                .CATEGORY_ID
                                .eq(CATEGORY.ID)
                                .and(PRODUCT_LISTING.STATUS.eq(PUBLISHED)))))
        .orderBy(CATEGORY.SLUG.asc())
        .fetch(r -> new CrawlEntry(r.value1(), r.value2()));
  }

  @Override
  public List<CrawlEntry> nonEmptyCollections(UUID orgId) {
    return dsl.select(COLLECTION.SLUG, COLLECTION.UPDATED_AT)
        .from(COLLECTION)
        .where(
            COLLECTION
                .ORG_ID
                .eq(orgId)
                .andExists(
                    selectOne()
                        .from(COLLECTION_LISTING)
                        .join(PRODUCT_LISTING)
                        .on(PRODUCT_LISTING.ID.eq(COLLECTION_LISTING.PRODUCT_LISTING_ID))
                        .where(
                            COLLECTION_LISTING
                                .COLLECTION_ID
                                .eq(COLLECTION.ID)
                                .and(PRODUCT_LISTING.STATUS.eq(PUBLISHED)))))
        .orderBy(COLLECTION.SLUG.asc())
        .fetch(r -> new CrawlEntry(r.value1(), r.value2()));
  }

  @Override
  public List<CrawlEntry> pages(UUID orgId) {
    return dsl.select(STOREFRONT_PAGE.KIND, STOREFRONT_PAGE.UPDATED_AT)
        .from(STOREFRONT_PAGE)
        .where(STOREFRONT_PAGE.ORG_ID.eq(orgId))
        .orderBy(STOREFRONT_PAGE.KIND.asc())
        .fetch(r -> new CrawlEntry(r.value1(), r.value2()));
  }

  @Override
  public boolean hasFeatured(UUID orgId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING.STATUS.eq(PUBLISHED))
                    .and(PRODUCT_LISTING.FEATURED_SORT.isNotNull())));
  }
}
