package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_IMAGE;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.ListingSort;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.repository.generated.tables.records.ProductListingCategoryRecord;
import com.loai.inventory.repository.generated.tables.records.ProductListingImageRecord;
import com.loai.inventory.repository.generated.tables.records.ProductListingRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SelectJoinStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProductListingRepositoryImpl implements ProductListingRepository {
  private static final Logger log = LoggerFactory.getLogger(ProductListingRepositoryImpl.class);
  private final DSLContext dsl;

  public ProductListingRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  // --- listing CRUD ---

  @Override
  public Optional<ProductListing> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.ID.eq(id)))
        .fetchOptional()
        .map(this::toListing);
  }

  @Override
  public List<CheckoutLineResolution> resolveForCheckout(
      UUID orgId, java.util.Collection<String> slugs, ListingStatus status) {
    if (slugs == null || slugs.isEmpty()) {
      return List.of();
    }
    return dsl.select(
            PRODUCT_LISTING.SLUG,
            PRODUCT_LISTING.PRODUCT_ID,
            PRODUCT_LISTING.SALES_PRICE,
            PRODUCT_LISTING.TITLE)
        .from(PRODUCT_LISTING)
        .where(
            PRODUCT_LISTING
                .ORG_ID
                .eq(orgId)
                .and(PRODUCT_LISTING.STATUS.eq(toGenerated(status)))
                .and(PRODUCT_LISTING.SLUG.in(slugs)))
        .fetch(
            r ->
                new CheckoutLineResolution(
                    r.get(PRODUCT_LISTING.SLUG),
                    r.get(PRODUCT_LISTING.PRODUCT_ID),
                    r.get(PRODUCT_LISTING.SALES_PRICE),
                    r.get(PRODUCT_LISTING.TITLE)));
  }

  @Override
  public List<ListingAvailability> resolveAvailability(
      UUID orgId, java.util.Collection<String> slugs, ListingStatus status) {
    if (slugs == null || slugs.isEmpty()) {
      return List.of();
    }
    // available = stock_qty - reserved_qty; untracked (no inventory row) → COALESCE 0. The join is
    // on (org_id, product_id) so a cross-org inventory row can never satisfy it.
    org.jooq.Field<Integer> available =
        org.jooq
            .impl
            .DSL
            .coalesce(
                INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY), org.jooq.impl.DSL.inline(0))
            .as("available");
    return dsl.select(PRODUCT_LISTING.SLUG, available)
        .from(PRODUCT_LISTING)
        .leftJoin(INVENTORY)
        .on(
            INVENTORY
                .ORG_ID
                .eq(PRODUCT_LISTING.ORG_ID)
                .and(INVENTORY.PRODUCT_ID.eq(PRODUCT_LISTING.PRODUCT_ID)))
        .where(
            PRODUCT_LISTING
                .ORG_ID
                .eq(orgId)
                .and(PRODUCT_LISTING.STATUS.eq(toGenerated(status)))
                .and(PRODUCT_LISTING.SLUG.in(slugs)))
        .fetch(
            r ->
                new ListingAvailability(
                    r.get(PRODUCT_LISTING.SLUG), r.get(available) == null ? 0 : r.get(available)));
  }

  @Override
  public List<ReorderResolution> resolveForReorder(
      UUID orgId, java.util.Collection<UUID> productIds, ListingStatus status) {
    if (productIds == null || productIds.isEmpty()) {
      return List.of();
    }
    // available = stock_qty - reserved_qty; untracked (no inventory row) → COALESCE 0. Same B2
    // signal as resolveAvailability, but keyed by product_id (the order line's handle) instead of
    // slug — a reorder starts from past order lines, which carry product_id, never a public slug.
    org.jooq.Field<Integer> available =
        org.jooq
            .impl
            .DSL
            .coalesce(
                INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY), org.jooq.impl.DSL.inline(0))
            .as("available");
    return dsl.select(
            PRODUCT_LISTING.PRODUCT_ID,
            PRODUCT_LISTING.SLUG,
            PRODUCT_LISTING.TITLE,
            PRODUCT_LISTING.SALES_PRICE,
            available)
        .from(PRODUCT_LISTING)
        .leftJoin(INVENTORY)
        .on(
            INVENTORY
                .ORG_ID
                .eq(PRODUCT_LISTING.ORG_ID)
                .and(INVENTORY.PRODUCT_ID.eq(PRODUCT_LISTING.PRODUCT_ID)))
        .where(
            PRODUCT_LISTING
                .ORG_ID
                .eq(orgId)
                .and(PRODUCT_LISTING.STATUS.eq(toGenerated(status)))
                .and(PRODUCT_LISTING.PRODUCT_ID.in(productIds)))
        .fetch(
            r ->
                new ReorderResolution(
                    r.get(PRODUCT_LISTING.PRODUCT_ID),
                    r.get(PRODUCT_LISTING.SLUG),
                    r.get(PRODUCT_LISTING.TITLE),
                    r.get(PRODUCT_LISTING.SALES_PRICE),
                    r.get(available) == null ? 0 : r.get(available)));
  }

  @Override
  public Optional<ProductListing> findBySlugAndStatus(
      UUID orgId, String slug, ListingStatus status) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(
            PRODUCT_LISTING
                .ORG_ID
                .eq(orgId)
                .and(PRODUCT_LISTING.SLUG.eq(slug))
                .and(PRODUCT_LISTING.STATUS.eq(toGenerated(status))))
        .fetchOptional()
        .map(this::toListing);
  }

  @Override
  public List<ProductListing> findByFilters(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      java.math.BigDecimal minPrice,
      java.math.BigDecimal maxPrice,
      boolean featuredOnly,
      ListingSort sort,
      int offset,
      int limit) {
    SelectJoinStep<Record> step = dsl.select(PRODUCT_LISTING.fields()).from(PRODUCT_LISTING);
    return joinCategoryIfNeeded(step, categoryId)
        .where(filterConditions(orgId, status, categoryId, q, minPrice, maxPrice, featuredOnly))
        .orderBy(orderFields(sort))
        .offset(offset)
        .limit(limit)
        .fetchInto(PRODUCT_LISTING)
        .map(this::toListing);
  }

  @Override
  public long countByFilters(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      java.math.BigDecimal minPrice,
      java.math.BigDecimal maxPrice,
      boolean featuredOnly) {
    SelectJoinStep<Record1<UUID>> step = dsl.select(PRODUCT_LISTING.ID).from(PRODUCT_LISTING);
    return dsl.fetchCount(
        joinCategoryIfNeeded(step, categoryId)
            .where(
                filterConditions(orgId, status, categoryId, q, minPrice, maxPrice, featuredOnly)));
  }

  /** The category narrow is a join only when requested — the unfiltered read stays join-free. */
  private static <R extends Record> SelectJoinStep<R> joinCategoryIfNeeded(
      SelectJoinStep<R> step, UUID categoryId) {
    if (categoryId == null) {
      return step;
    }
    return step.join(PRODUCT_LISTING_CATEGORY)
        .on(PRODUCT_LISTING_CATEGORY.LISTING_ID.eq(PRODUCT_LISTING.ID));
  }

  /**
   * The shared predicate set behind {@link #findByFilters} and {@link #countByFilters}: org +
   * status always; category / substring / price bounds each only when present. {@code q} is bound
   * as a parameter ({@code lower(col) LIKE lower(?)} — ILIKE semantics, never interpolated); {@code
   * %}/{@code _} in the term are treated as literal-enough user text at per-org published scale (no
   * escaping in v1, per the B3 story).
   */
  private static Condition filterConditions(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      java.math.BigDecimal minPrice,
      java.math.BigDecimal maxPrice,
      boolean featuredOnly) {
    Condition c =
        PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.STATUS.eq(toGenerated(status)));
    if (categoryId != null) {
      c = c.and(PRODUCT_LISTING_CATEGORY.CATEGORY_ID.eq(categoryId));
    }
    if (featuredOnly) {
      c = c.and(PRODUCT_LISTING.FEATURED_SORT.isNotNull());
    }
    if (q != null) {
      String pattern = "%" + q + "%";
      c =
          c.and(
              PRODUCT_LISTING
                  .TITLE
                  .likeIgnoreCase(pattern)
                  .or(PRODUCT_LISTING.MARKETING_COPY.likeIgnoreCase(pattern)));
    }
    if (minPrice != null) {
      c = c.and(PRODUCT_LISTING.SALES_PRICE.ge(minPrice));
    }
    if (maxPrice != null) {
      c = c.and(PRODUCT_LISTING.SALES_PRICE.le(maxPrice));
    }
    return c;
  }

  /** Every sort is tie-broken by {@code slug ASC} (unique per org) so paging is deterministic. */
  private static List<OrderField<?>> orderFields(ListingSort sort) {
    return switch (sort) {
      case NEWEST ->
          List.of(PRODUCT_LISTING.PUBLISHED_AT.desc().nullsLast(), PRODUCT_LISTING.SLUG.asc());
      case PRICE_ASC -> List.of(PRODUCT_LISTING.SALES_PRICE.asc(), PRODUCT_LISTING.SLUG.asc());
      case PRICE_DESC -> List.of(PRODUCT_LISTING.SALES_PRICE.desc(), PRODUCT_LISTING.SLUG.asc());
      case FEATURED ->
          List.of(PRODUCT_LISTING.FEATURED_SORT.asc().nullsLast(), PRODUCT_LISTING.SLUG.asc());
    };
  }

  @Override
  public List<ProductListing> findAll(UUID orgId, int offset, int limit) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId))
        .orderBy(PRODUCT_LISTING.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toListing);
  }

  @Override
  public List<ProductListing> findAllByStatus(
      UUID orgId, ListingStatus status, int offset, int limit) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.STATUS.eq(toGenerated(status))))
        .orderBy(PRODUCT_LISTING.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toListing);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(dsl.selectFrom(PRODUCT_LISTING).where(PRODUCT_LISTING.ORG_ID.eq(orgId)));
  }

  @Override
  public long countByStatus(UUID orgId, ListingStatus status) {
    return dsl.fetchCount(
        dsl.selectFrom(PRODUCT_LISTING)
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING.STATUS.eq(toGenerated(status)))));
  }

  // --- featured curation (slice C3) ---

  @Override
  public List<ProductListing> findFeatured(UUID orgId) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.FEATURED_SORT.isNotNull()))
        .orderBy(PRODUCT_LISTING.FEATURED_SORT.asc(), PRODUCT_LISTING.SLUG.asc())
        .fetch()
        .map(this::toListing);
  }

  @Override
  public long countInOrg(UUID orgId, java.util.Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return 0L;
    }
    return dsl.fetchCount(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.ID.in(ids))));
  }

  @Override
  public void setFeatured(UUID orgId, List<UUID> orderedIds) {
    // Clear the whole org's featured list first, then stamp featured_sort = index on the kept ids.
    // Two statements in the caller's transaction → the set-replace is atomic and idempotent; the
    // partial index keeps the clear cheap (only the ≤ 12 currently-featured rows carry a non-null).
    dsl.update(PRODUCT_LISTING)
        .setNull(PRODUCT_LISTING.FEATURED_SORT)
        .set(PRODUCT_LISTING.UPDATED_AT, OffsetDateTime.now())
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.FEATURED_SORT.isNotNull()))
        .execute();
    for (int i = 0; i < orderedIds.size(); i++) {
      dsl.update(PRODUCT_LISTING)
          .set(PRODUCT_LISTING.FEATURED_SORT, i)
          .set(PRODUCT_LISTING.UPDATED_AT, OffsetDateTime.now())
          .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.ID.eq(orderedIds.get(i))))
          .execute();
    }
  }

  @Override
  public ProductListing insert(ProductListing listing) {
    ProductListingRecord record =
        dsl.insertInto(PRODUCT_LISTING)
            .set(PRODUCT_LISTING.ORG_ID, listing.getOrgId())
            .set(PRODUCT_LISTING.PRODUCT_ID, listing.getProductId())
            .set(PRODUCT_LISTING.TITLE, listing.getTitle())
            .set(PRODUCT_LISTING.MARKETING_COPY, listing.getMarketingCopy())
            .set(PRODUCT_LISTING.SLUG, listing.getSlug())
            .set(PRODUCT_LISTING.SALES_PRICE, listing.getSalesPrice())
            .set(PRODUCT_LISTING.STATUS, toGenerated(listing.getStatus()))
            .set(PRODUCT_LISTING.PUBLISHED_AT, listing.getPublishedAt())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into product_listing returned no record");
    }
    log.debug("Inserted product_listing id={} orgId={}", record.getId(), record.getOrgId());
    return toListing(record);
  }

  @Override
  public ProductListing update(ProductListing listing) {
    ProductListingRecord record =
        dsl.update(PRODUCT_LISTING)
            .set(PRODUCT_LISTING.TITLE, listing.getTitle())
            .set(PRODUCT_LISTING.MARKETING_COPY, listing.getMarketingCopy())
            .set(PRODUCT_LISTING.SLUG, listing.getSlug())
            .set(PRODUCT_LISTING.SALES_PRICE, listing.getSalesPrice())
            .set(PRODUCT_LISTING.UPDATED_AT, OffsetDateTime.now())
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(listing.getOrgId())
                    .and(PRODUCT_LISTING.ID.eq(listing.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("ProductListing", listing.getId());
    }
    log.debug("Updated product_listing id={}", record.getId());
    return toListing(record);
  }

  @Override
  public ProductListing updateStatus(ProductListing listing) {
    ProductListingRecord record =
        dsl.update(PRODUCT_LISTING)
            .set(PRODUCT_LISTING.STATUS, toGenerated(listing.getStatus()))
            .set(PRODUCT_LISTING.PUBLISHED_AT, listing.getPublishedAt())
            .set(PRODUCT_LISTING.UPDATED_AT, OffsetDateTime.now())
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(listing.getOrgId())
                    .and(PRODUCT_LISTING.ID.eq(listing.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("ProductListing", listing.getId());
    }
    log.debug(
        "Updated product_listing status id={} status={}", record.getId(), listing.getStatus());
    return toListing(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(PRODUCT_LISTING)
            .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.ID.eq(id)))
            .execute();
    if (deleted == 0) {
      throw new NotFoundException("ProductListing", id);
    }
  }

  @Override
  public boolean existsByProductId(UUID orgId, UUID productId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.PRODUCT_ID.eq(productId))));
  }

  @Override
  public boolean existsBySlug(UUID orgId, String slug) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.SLUG.eq(slug))));
  }

  @Override
  public boolean existsBySlugAndIdNot(UUID orgId, String slug, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING.SLUG.eq(slug))
                    .and(PRODUCT_LISTING.ID.ne(excludeId))));
  }

  @Override
  public boolean productExists(UUID orgId, UUID productId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT)
            .where(PRODUCT.ORG_ID.eq(orgId).and(PRODUCT.ID.eq(productId))));
  }

  // --- listing ⇄ category ---

  @Override
  public void replaceCategories(UUID listingId, Set<UUID> categoryIds) {
    dsl.deleteFrom(PRODUCT_LISTING_CATEGORY)
        .where(PRODUCT_LISTING_CATEGORY.LISTING_ID.eq(listingId))
        .execute();
    if (categoryIds.isEmpty()) {
      return;
    }
    List<ProductListingCategoryRecord> rows = new ArrayList<>(categoryIds.size());
    for (UUID categoryId : categoryIds) {
      ProductListingCategoryRecord r = dsl.newRecord(PRODUCT_LISTING_CATEGORY);
      r.setListingId(listingId);
      r.setCategoryId(categoryId);
      rows.add(r);
    }
    dsl.batchInsert(rows).execute();
  }

  @Override
  public List<UUID> findCategoryIds(UUID listingId) {
    return dsl.select(PRODUCT_LISTING_CATEGORY.CATEGORY_ID)
        .from(PRODUCT_LISTING_CATEGORY)
        .where(PRODUCT_LISTING_CATEGORY.LISTING_ID.eq(listingId))
        .fetch(PRODUCT_LISTING_CATEGORY.CATEGORY_ID);
  }

  @Override
  public java.util.Map<UUID, List<UUID>> findCategoryIdsForListings(
      java.util.Collection<UUID> listingIds) {
    if (listingIds.isEmpty()) {
      return java.util.Map.of();
    }
    return dsl.select(PRODUCT_LISTING_CATEGORY.LISTING_ID, PRODUCT_LISTING_CATEGORY.CATEGORY_ID)
        .from(PRODUCT_LISTING_CATEGORY)
        .where(PRODUCT_LISTING_CATEGORY.LISTING_ID.in(listingIds))
        .fetchGroups(PRODUCT_LISTING_CATEGORY.LISTING_ID, PRODUCT_LISTING_CATEGORY.CATEGORY_ID);
  }

  @Override
  public long countCategoriesInOrg(UUID orgId, Set<UUID> categoryIds) {
    if (categoryIds.isEmpty()) {
      return 0L;
    }
    return dsl.fetchCount(
        dsl.selectOne()
            .from(CATEGORY)
            .where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.ID.in(categoryIds))));
  }

  // --- images ---

  @Override
  public ProductListingImage insertImage(ProductListingImage image) {
    ProductListingImageRecord record =
        dsl.insertInto(PRODUCT_LISTING_IMAGE)
            .set(PRODUCT_LISTING_IMAGE.ORG_ID, image.getOrgId())
            .set(PRODUCT_LISTING_IMAGE.LISTING_ID, image.getListingId())
            .set(PRODUCT_LISTING_IMAGE.OBJECT_KEY, image.getObjectKey())
            .set(PRODUCT_LISTING_IMAGE.ALT_TEXT, image.getAltText())
            .set(PRODUCT_LISTING_IMAGE.SORT_ORDER, image.getSortOrder())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into product_listing_image returned no record");
    }
    return toImage(record);
  }

  @Override
  public List<ProductListingImage> findImages(UUID listingId) {
    return dsl.selectFrom(PRODUCT_LISTING_IMAGE)
        .where(PRODUCT_LISTING_IMAGE.LISTING_ID.eq(listingId))
        .orderBy(PRODUCT_LISTING_IMAGE.SORT_ORDER.asc(), PRODUCT_LISTING_IMAGE.CREATED_AT.asc())
        .fetch()
        .map(this::toImage);
  }

  @Override
  public List<ProductListingImage> findImagesForListings(java.util.Collection<UUID> listingIds) {
    if (listingIds.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(PRODUCT_LISTING_IMAGE)
        .where(PRODUCT_LISTING_IMAGE.LISTING_ID.in(listingIds))
        .orderBy(
            PRODUCT_LISTING_IMAGE.LISTING_ID.asc(),
            PRODUCT_LISTING_IMAGE.SORT_ORDER.asc(),
            PRODUCT_LISTING_IMAGE.CREATED_AT.asc())
        .fetch()
        .map(this::toImage);
  }

  @Override
  public Map<UUID, String> findPrimaryImageObjectKeys(
      UUID orgId, java.util.Collection<UUID> productIds) {
    if (productIds.isEmpty()) {
      return Map.of();
    }
    // Ordered product → sort_order → created_at, so the first row seen per product is its primary
    // image; putIfAbsent keeps it (no DISTINCT ON needed, and a page is a bounded set of products).
    Map<UUID, String> keys = new HashMap<>();
    dsl.select(PRODUCT_LISTING.PRODUCT_ID, PRODUCT_LISTING_IMAGE.OBJECT_KEY)
        .from(PRODUCT_LISTING)
        .join(PRODUCT_LISTING_IMAGE)
        .on(PRODUCT_LISTING_IMAGE.LISTING_ID.eq(PRODUCT_LISTING.ID))
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.PRODUCT_ID.in(productIds)))
        .orderBy(
            PRODUCT_LISTING.PRODUCT_ID.asc(),
            PRODUCT_LISTING_IMAGE.SORT_ORDER.asc(),
            PRODUCT_LISTING_IMAGE.CREATED_AT.asc())
        .forEach(r -> keys.putIfAbsent(r.value1(), r.value2()));
    return keys;
  }

  @Override
  public ProductListingImage updateImage(
      UUID orgId, UUID listingId, UUID imageId, String altText, int sortOrder) {
    ProductListingImageRecord record =
        dsl.update(PRODUCT_LISTING_IMAGE)
            .set(PRODUCT_LISTING_IMAGE.ALT_TEXT, altText)
            .set(PRODUCT_LISTING_IMAGE.SORT_ORDER, sortOrder)
            .where(
                PRODUCT_LISTING_IMAGE
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING_IMAGE.LISTING_ID.eq(listingId))
                    .and(PRODUCT_LISTING_IMAGE.ID.eq(imageId)))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("ProductListingImage", imageId);
    }
    return toImage(record);
  }

  @Override
  public void deleteImage(UUID orgId, UUID listingId, UUID imageId) {
    int deleted =
        dsl.deleteFrom(PRODUCT_LISTING_IMAGE)
            .where(
                PRODUCT_LISTING_IMAGE
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING_IMAGE.LISTING_ID.eq(listingId))
                    .and(PRODUCT_LISTING_IMAGE.ID.eq(imageId)))
            .execute();
    if (deleted == 0) {
      throw new NotFoundException("ProductListingImage", imageId);
    }
  }

  // --- mappers / enum bridge ---

  private static com.loai.inventory.repository.generated.enums.ListingStatus toGenerated(
      ListingStatus status) {
    return com.loai.inventory.repository.generated.enums.ListingStatus.valueOf(status.name());
  }

  private ProductListing toListing(ProductListingRecord r) {
    return new ProductListing(
        r.getId(),
        r.getOrgId(),
        r.getProductId(),
        r.getTitle(),
        r.getMarketingCopy(),
        r.getSlug(),
        r.getSalesPrice(),
        ListingStatus.valueOf(r.getStatus().name()),
        r.getPublishedAt(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }

  private ProductListingImage toImage(ProductListingImageRecord r) {
    return new ProductListingImage(
        r.getId(),
        r.getOrgId(),
        r.getListingId(),
        r.getObjectKey(),
        r.getAltText(),
        r.getSortOrder() == null ? 0 : r.getSortOrder(),
        r.getCreatedAt());
  }
}
