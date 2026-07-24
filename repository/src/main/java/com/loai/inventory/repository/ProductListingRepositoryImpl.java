package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_IMAGE;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.ListingSort;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.model.ProductListingTranslation;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.repository.generated.tables.records.ProductListingCategoryRecord;
import com.loai.inventory.repository.generated.tables.records.ProductListingImageRecord;
import com.loai.inventory.repository.generated.tables.records.ProductListingRecord;
import com.loai.inventory.repository.generated.tables.records.ProductListingTranslationRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
import org.jooq.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProductListingRepositoryImpl implements ProductListingRepository {
  private static final Logger log = LoggerFactory.getLogger(ProductListingRepositoryImpl.class);

  /**
   * The best-sellers ranking window ({@code stories/storefront_best_sellers.md}, roadmap item 4). A
   * fixed constant, not a query parameter — a {@code ?window=} would fragment the 60s edge cache
   * key for no merchandising gain. Widening to 90 days is this one line.
   */
  private static final int BEST_SELLING_WINDOW_DAYS = 30;

  /**
   * Orders whose lines count as sold units — money committed. The same set as the reporting slice's
   * {@code SALE_STATUSES}: DRAFT/PENDING_PAYMENT are not yet money, CANCELLED/EXPIRED never were.
   */
  private static final com.loai.inventory.repository.generated.enums.OrderStatus[] SALE_STATUSES = {
    com.loai.inventory.repository.generated.enums.OrderStatus.PAID,
    com.loai.inventory.repository.generated.enums.OrderStatus.FULFILLING,
    com.loai.inventory.repository.generated.enums.OrderStatus.FULFILLED,
    com.loai.inventory.repository.generated.enums.OrderStatus.CLOSED
  };

  /** Column name of the derived best-sellers aggregate — referenced back off the joined table. */
  private static final String SOLD_UNITS = "sold_units";

  private final DSLContext dsl;

  public ProductListingRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The default-locale translation row, aliased for the base reads. Since L6 dropped the legacy
   * {@code product_listing.title}/{@code marketing_copy} columns, the domain object's single {@code
   * title}/{@code marketingCopy} (the admin-plane display value, the comment-notification payload,
   * …) is sourced here: the row whose {@code language} equals the org's {@code default_locale} (NOT
   * NULL since V52; a default-locale row is guaranteed to exist by the write rule + L1 backfill). A
   * {@code LEFT} join keeps a listing visible even in the impossible case of a missing row (title
   * then null).
   */
  private static final com.loai.inventory.repository.generated.tables.ProductListingTranslation
      DEFAULT_T = PRODUCT_LISTING_TRANSLATION.as("default_t");

  /**
   * {@code SELECT product_listing.*, default_t.title, default_t.marketing_copy} joined to the org's
   * default-locale translation row — the base read shape behind every listing fetch that maps to a
   * {@link ProductListing}. {@link #toListing(Record)} reads the resolved scalar from it.
   */
  private SelectJoinStep<Record> selectListing() {
    return dsl.select(PRODUCT_LISTING.fields())
        .select(DEFAULT_T.TITLE, DEFAULT_T.MARKETING_COPY)
        .from(PRODUCT_LISTING)
        .join(ORG)
        .on(ORG.ID.eq(PRODUCT_LISTING.ORG_ID))
        .leftJoin(DEFAULT_T)
        .on(
            DEFAULT_T
                .LISTING_ID
                .eq(PRODUCT_LISTING.ID)
                .and(DEFAULT_T.LANGUAGE.eq(ORG.DEFAULT_LOCALE)));
  }

  // --- listing CRUD ---

  @Override
  public Optional<ProductListing> findById(UUID orgId, UUID id) {
    return selectListing()
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.ID.eq(id)))
        .fetchOptional()
        .map(this::toListing);
  }

  @Override
  public List<CheckoutLineResolution> resolveForCheckout(
      UUID orgId,
      java.util.Collection<String> slugs,
      ListingStatus status,
      String locale,
      String defaultLocale) {
    if (slugs == null || slugs.isEmpty()) {
      return List.of();
    }
    // Resolve the title to the checkout locale (L2b): the requested-locale translation row, else
    // the default-locale row — via two LEFT JOINs + COALESCE, one query, mirroring the L2 read
    // resolver. When locale == defaultLocale both joins hit the same row (harmless — COALESCE still
    // lands on it). A default-locale row is guaranteed (write rule + L1 backfill), so title is
    // never
    // null. (The legacy product_listing.title fallback arm was dropped at L6.)
    var reqT = PRODUCT_LISTING_TRANSLATION.as("req_t");
    var defT = PRODUCT_LISTING_TRANSLATION.as("def_t");
    org.jooq.Field<String> resolvedTitle =
        org.jooq.impl.DSL.coalesce(reqT.TITLE, defT.TITLE).as("title");
    return dsl.select(
            PRODUCT_LISTING.SLUG,
            PRODUCT_LISTING.PRODUCT_ID,
            PRODUCT_LISTING.SALES_PRICE,
            resolvedTitle)
        .from(PRODUCT_LISTING)
        .leftJoin(reqT)
        .on(reqT.LISTING_ID.eq(PRODUCT_LISTING.ID).and(reqT.LANGUAGE.eq(locale)))
        .leftJoin(defT)
        .on(defT.LISTING_ID.eq(PRODUCT_LISTING.ID).and(defT.LANGUAGE.eq(defaultLocale)))
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
                    r.get(resolvedTitle)));
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
    // The reorder label is the listing title in the org's default locale (L6 — sourced from the
    // default-locale translation row now that product_listing.title is gone). A default-locale row
    // is guaranteed, so the title is never null.
    return dsl.select(
            PRODUCT_LISTING.PRODUCT_ID,
            PRODUCT_LISTING.SLUG,
            DEFAULT_T.TITLE,
            PRODUCT_LISTING.SALES_PRICE,
            available)
        .from(PRODUCT_LISTING)
        .join(ORG)
        .on(ORG.ID.eq(PRODUCT_LISTING.ORG_ID))
        .leftJoin(DEFAULT_T)
        .on(
            DEFAULT_T
                .LISTING_ID
                .eq(PRODUCT_LISTING.ID)
                .and(DEFAULT_T.LANGUAGE.eq(ORG.DEFAULT_LOCALE)))
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
                    r.get(DEFAULT_T.TITLE),
                    r.get(PRODUCT_LISTING.SALES_PRICE),
                    r.get(available) == null ? 0 : r.get(available)));
  }

  @Override
  public Optional<ProductListing> findBySlugAndStatus(
      UUID orgId, String slug, ListingStatus status) {
    return selectListing()
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
  public Optional<ProductListing> findBySlug(UUID orgId, String slug) {
    return selectListing()
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.SLUG.eq(slug)))
        .fetchOptional()
        .map(this::toListing);
  }

  @Override
  public Map<UUID, String> findSlugsByProductIds(UUID orgId, Collection<UUID> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    return dsl.select(PRODUCT_LISTING.PRODUCT_ID, PRODUCT_LISTING.SLUG)
        .from(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.PRODUCT_ID.in(productIds)))
        .fetchMap(PRODUCT_LISTING.PRODUCT_ID, PRODUCT_LISTING.SLUG);
  }

  @Override
  public List<ProductListing> findByFilters(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      String locale,
      String defaultLocale,
      java.math.BigDecimal minPrice,
      java.math.BigDecimal maxPrice,
      boolean featuredOnly,
      boolean soldOnly,
      ListingSort sort,
      int offset,
      int limit) {
    Table<?> sold = soldOnly || sort == ListingSort.BEST_SELLING ? soldUnits(orgId) : null;
    return joinSoldIfNeeded(joinCategoryIfNeeded(selectListing(), categoryId), sold)
        .where(
            filterConditions(
                orgId,
                status,
                categoryId,
                q,
                locale,
                defaultLocale,
                minPrice,
                maxPrice,
                featuredOnly,
                soldOnly ? soldUnitsField(sold) : null))
        .orderBy(orderFields(sort, soldUnitsField(sold)))
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toListing);
  }

  @Override
  public long countByFilters(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      String locale,
      String defaultLocale,
      java.math.BigDecimal minPrice,
      java.math.BigDecimal maxPrice,
      boolean featuredOnly,
      boolean soldOnly) {
    SelectJoinStep<Record1<UUID>> step = dsl.select(PRODUCT_LISTING.ID).from(PRODUCT_LISTING);
    // The count mirrors findByFilters' predicates only — the sold aggregate is joined here just for
    // the ?sold=true narrow; the BEST_SELLING *order* changes no row's membership, so a plain
    // ?sort=best_selling count never pays for the join.
    Table<?> sold = soldOnly ? soldUnits(orgId) : null;
    return dsl.fetchCount(
        joinSoldIfNeeded(joinCategoryIfNeeded(step, categoryId), sold)
            .where(
                filterConditions(
                    orgId,
                    status,
                    categoryId,
                    q,
                    locale,
                    defaultLocale,
                    minPrice,
                    maxPrice,
                    featuredOnly,
                    soldUnitsField(sold))));
  }

  /**
   * The best-sellers aggregate ({@code stories/storefront_best_sellers.md}): units actually sold
   * per {@code product_id} over the rolling window, as a derived table to LEFT-JOIN onto the
   * listing query. Semantics are deliberately identical to the reporting slice's {@code
   * ReportRepositoryImpl.topProducts} so the two reads never disagree — money-committed statuses
   * only, {@code COALESCE(placed_at, created_at)} as the sale timestamp, half-open window, {@code
   * SUM(quantity)} (units, not revenue). Status is read <em>live</em>, so an order that cancels
   * drops out of the ranking on the next 60s cache refresh.
   */
  private static Table<?> soldUnits(UUID orgId) {
    // The window is anchored to the DATABASE clock, not the JVM's: an order's placed_at/created_at
    // is stamped by Postgres, so bounding it with a JVM timestamp makes a fresh sale invisible
    // whenever the app server's clock trails the DB's by more than the request took to arrive.
    org.jooq.Field<OffsetDateTime> now = org.jooq.impl.DSL.field("now()", OffsetDateTime.class);
    org.jooq.Field<OffsetDateTime> windowStart =
        org.jooq.impl.DSL.field(
            "now() - make_interval(days => {0})",
            OffsetDateTime.class, org.jooq.impl.DSL.val(BEST_SELLING_WINDOW_DAYS));
    org.jooq.Field<OffsetDateTime> saleTs =
        org.jooq.impl.DSL.coalesce(SALES_ORDER.PLACED_AT, SALES_ORDER.CREATED_AT);
    return org.jooq
        .impl
        .DSL
        .select(
            SALES_ORDER_LINE.PRODUCT_ID,
            org.jooq.impl.DSL.sum(SALES_ORDER_LINE.QUANTITY).as(SOLD_UNITS))
        .from(SALES_ORDER_LINE)
        .join(SALES_ORDER)
        .on(SALES_ORDER_LINE.SALES_ORDER_ID.eq(SALES_ORDER.ID))
        .where(
            SALES_ORDER
                .ORG_ID
                .eq(orgId)
                .and(SALES_ORDER.STATUS.in(SALE_STATUSES))
                .and(saleTs.ge(windowStart))
                .and(saleTs.lt(now)))
        .groupBy(SALES_ORDER_LINE.PRODUCT_ID)
        .asTable("sold");
  }

  /** The derived table's {@code SUM(quantity)} column, or null when the aggregate isn't joined. */
  private static org.jooq.Field<java.math.BigDecimal> soldUnitsField(Table<?> sold) {
    return sold == null ? null : sold.field(SOLD_UNITS, java.math.BigDecimal.class);
  }

  /** LEFT JOIN so never-sold listings survive the join and rank last (COALESCE 0), never vanish. */
  private static <R extends Record> SelectJoinStep<R> joinSoldIfNeeded(
      SelectJoinStep<R> step, Table<?> sold) {
    if (sold == null) {
      return step;
    }
    return step.leftJoin(sold)
        .on(sold.field(SALES_ORDER_LINE.PRODUCT_ID).eq(PRODUCT_LISTING.PRODUCT_ID));
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
   * status always; category / substring / price bounds each only when present.
   *
   * <p>The text match (slice L2) runs against the per-language {@code product_listing_translation}
   * rows in {@code (locale, defaultLocale)}: the Arabic-folded {@code title_search} (GIN, so {@code
   * "احمد"} matches an {@code "أحمد"} title) OR the plain {@code marketing_copy} substring. It is
   * an {@code EXISTS} so a listing surfaces once even when both its locale rows match. {@code
   * %}/{@code _} in the term are treated as literal-enough user text at per-org published scale (no
   * escaping in v1, per the B3 story). {@code locale}/{@code defaultLocale} are ignored when {@code
   * q} is null.
   */
  private static Condition filterConditions(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      String locale,
      String defaultLocale,
      java.math.BigDecimal minPrice,
      java.math.BigDecimal maxPrice,
      boolean featuredOnly,
      org.jooq.Field<java.math.BigDecimal> soldUnits) {
    Condition c =
        PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.STATUS.eq(toGenerated(status)));
    if (soldUnits != null) {
      // LEFT JOIN + NULL-fails-the-predicate: a never-sold listing is simply absent, no COALESCE
      // needed. Only reached when the caller asked for ?sold=true.
      c = c.and(soldUnits.gt(java.math.BigDecimal.ZERO));
    }
    if (categoryId != null) {
      c = c.and(PRODUCT_LISTING_CATEGORY.CATEGORY_ID.eq(categoryId));
    }
    if (featuredOnly) {
      c = c.and(PRODUCT_LISTING.FEATURED_SORT.isNotNull());
    }
    if (q != null) {
      // Match the per-language translation rows (Arabic-folded title_search OR marketing_copy
      // substring) in the resolved locales. The legacy product_listing.title/marketing_copy OR-arms
      // were dropped at L6 — the translation table is the only text source now.
      c = c.and(translationSearchExists(q, locale, defaultLocale));
    }
    if (minPrice != null) {
      c = c.and(PRODUCT_LISTING.SALES_PRICE.ge(minPrice));
    }
    if (maxPrice != null) {
      c = c.and(PRODUCT_LISTING.SALES_PRICE.le(maxPrice));
    }
    return c;
  }

  /**
   * EXISTS a translation row for the listing, in one of the resolved locales, whose folded {@code
   * title_search} contains the folded term (Arabic-aware, GIN-indexed) or whose {@code
   * marketing_copy} contains the raw term. Distinct locale names are de-duplicated so a listing
   * whose {@code locale == defaultLocale} scans one language, not two.
   */
  private static Condition translationSearchExists(String q, String locale, String defaultLocale) {
    String pattern = "%" + q + "%";
    org.jooq.Field<String> foldedTerm =
        org.jooq.impl.DSL.field("fold_search({0})", String.class, org.jooq.impl.DSL.val(q));
    java.util.LinkedHashSet<String> langs = new java.util.LinkedHashSet<>();
    if (locale != null) {
      langs.add(locale);
    }
    if (defaultLocale != null) {
      langs.add(defaultLocale);
    }
    return org.jooq.impl.DSL.exists(
        org.jooq
            .impl
            .DSL
            .selectOne()
            .from(PRODUCT_LISTING_TRANSLATION)
            .where(
                PRODUCT_LISTING_TRANSLATION
                    .LISTING_ID
                    .eq(PRODUCT_LISTING.ID)
                    .and(PRODUCT_LISTING_TRANSLATION.LANGUAGE.in(langs))
                    .and(
                        PRODUCT_LISTING_TRANSLATION
                            .TITLE_SEARCH
                            .like(
                                org.jooq.impl.DSL.concat(
                                    org.jooq.impl.DSL.inline("%"),
                                    foldedTerm,
                                    org.jooq.impl.DSL.inline("%")))
                            .or(
                                PRODUCT_LISTING_TRANSLATION.MARKETING_COPY.likeIgnoreCase(
                                    pattern)))));
  }

  /**
   * Every sort is tie-broken by {@code slug ASC} (unique per org) so paging is deterministic.
   * {@code soldUnits} is the joined best-sellers aggregate — non-null exactly when {@code sort ==
   * BEST_SELLING} (or {@code ?sold=true} joined it anyway) and unused otherwise.
   */
  private static List<OrderField<?>> orderFields(
      ListingSort sort, org.jooq.Field<java.math.BigDecimal> soldUnits) {
    return switch (sort) {
      case NEWEST ->
          List.of(PRODUCT_LISTING.PUBLISHED_AT.desc().nullsLast(), PRODUCT_LISTING.SLUG.asc());
      case PRICE_ASC -> List.of(PRODUCT_LISTING.SALES_PRICE.asc(), PRODUCT_LISTING.SLUG.asc());
      case PRICE_DESC -> List.of(PRODUCT_LISTING.SALES_PRICE.desc(), PRODUCT_LISTING.SLUG.asc());
      case FEATURED ->
          List.of(PRODUCT_LISTING.FEATURED_SORT.asc().nullsLast(), PRODUCT_LISTING.SLUG.asc());
      // COALESCE(0) rather than nullsLast(): a never-sold listing must sort *equal to* a listing
      // whose window sum is genuinely zero, and then fall to the slug tie-break with it.
      case BEST_SELLING ->
          List.of(
              org.jooq.impl.DSL.coalesce(soldUnits, java.math.BigDecimal.ZERO).desc(),
              PRODUCT_LISTING.SLUG.asc());
    };
  }

  @Override
  public List<ProductListing> findAll(UUID orgId, int offset, int limit) {
    return selectListing()
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
    return selectListing()
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
    return selectListing()
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

  // --- translations (content-localization slice L2) ---

  @Override
  public void replaceTranslations(UUID listingId, List<ProductListingTranslation> translations) {
    dsl.deleteFrom(PRODUCT_LISTING_TRANSLATION)
        .where(PRODUCT_LISTING_TRANSLATION.LISTING_ID.eq(listingId))
        .execute();
    if (translations == null || translations.isEmpty()) {
      return;
    }
    List<ProductListingTranslationRecord> rows = new ArrayList<>(translations.size());
    for (ProductListingTranslation t : translations) {
      ProductListingTranslationRecord r = dsl.newRecord(PRODUCT_LISTING_TRANSLATION);
      r.setListingId(listingId);
      r.setLanguage(t.language());
      r.setTitle(t.title());
      r.setMarketingCopy(t.marketingCopy());
      rows.add(r);
    }
    dsl.batchInsert(rows).execute();
  }

  @Override
  public List<ProductListingTranslation> findTranslations(UUID listingId) {
    return dsl.selectFrom(PRODUCT_LISTING_TRANSLATION)
        .where(PRODUCT_LISTING_TRANSLATION.LISTING_ID.eq(listingId))
        .orderBy(PRODUCT_LISTING_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .map(ProductListingRepositoryImpl::toTranslation);
  }

  @Override
  public Map<UUID, List<ProductListingTranslation>> findTranslationsForListings(
      Collection<UUID> listingIds) {
    if (listingIds == null || listingIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<ProductListingTranslation>> byListing = new HashMap<>();
    dsl.selectFrom(PRODUCT_LISTING_TRANSLATION)
        .where(PRODUCT_LISTING_TRANSLATION.LISTING_ID.in(listingIds))
        .orderBy(
            PRODUCT_LISTING_TRANSLATION.LISTING_ID.asc(),
            PRODUCT_LISTING_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .forEach(
            r ->
                byListing
                    .computeIfAbsent(r.getListingId(), k -> new ArrayList<>())
                    .add(toTranslation(r)));
    return byListing;
  }

  private static ProductListingTranslation toTranslation(ProductListingTranslationRecord r) {
    return new ProductListingTranslation(r.getLanguage(), r.getTitle(), r.getMarketingCopy());
  }

  // --- mappers / enum bridge ---

  private static com.loai.inventory.repository.generated.enums.ListingStatus toGenerated(
      ListingStatus status) {
    return com.loai.inventory.repository.generated.enums.ListingStatus.valueOf(status.name());
  }

  /**
   * Map a listing row. The single {@code title}/{@code marketingCopy} is the org's default-locale
   * translation, joined in by {@link #selectListing()} (and {@code default_t} aliased columns): a
   * joined read carries them; a bare {@code product_listing} record from an INSERT/UPDATE {@code
   * RETURNING} does not, so {@code field(...) == null} → scalar null (the service fills those
   * return values from the write's default-locale row — the columns themselves are gone since L6).
   */
  private ProductListing toListing(Record r) {
    String title = r.field(DEFAULT_T.TITLE) == null ? null : r.get(DEFAULT_T.TITLE);
    String marketingCopy =
        r.field(DEFAULT_T.MARKETING_COPY) == null ? null : r.get(DEFAULT_T.MARKETING_COPY);
    return new ProductListing(
        r.get(PRODUCT_LISTING.ID),
        r.get(PRODUCT_LISTING.ORG_ID),
        r.get(PRODUCT_LISTING.PRODUCT_ID),
        title,
        marketingCopy,
        r.get(PRODUCT_LISTING.SLUG),
        r.get(PRODUCT_LISTING.SALES_PRICE),
        ListingStatus.valueOf(r.get(PRODUCT_LISTING.STATUS).name()),
        r.get(PRODUCT_LISTING.PUBLISHED_AT),
        r.get(PRODUCT_LISTING.CREATED_AT),
        r.get(PRODUCT_LISTING.UPDATED_AT));
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
