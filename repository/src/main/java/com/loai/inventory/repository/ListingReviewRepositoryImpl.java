package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT_LINE;
import static com.loai.inventory.repository.generated.Tables.LISTING_REVIEW;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;

import com.loai.inventory.domain.model.ListingReview;
import com.loai.inventory.domain.model.ReviewStatus;
import com.loai.inventory.domain.repository.ListingReviewRepository;
import com.loai.inventory.repository.generated.tables.records.ListingReviewRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.impl.DSL;

public final class ListingReviewRepositoryImpl implements ListingReviewRepository {

  private final DSLContext dsl;

  /**
   * The listing's default-locale title, aliased for the worklist reads. Since L6 dropped {@code
   * product_listing.title}, the merchant-facing display title (My reviews, the moderation queue) is
   * resolved from the {@code product_listing_translation} row whose {@code language} equals the
   * org's {@code default_locale} (NOT NULL since V52; a default-locale row is guaranteed).
   */
  private static final com.loai.inventory.repository.generated.tables.ProductListingTranslation
      DEFAULT_T = PRODUCT_LISTING_TRANSLATION.as("default_t");

  public ListingReviewRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public boolean hasDeliveredProduct(UUID orgId, UUID customerId, UUID productId) {
    // EXISTS fulfillment_line JOIN sales_order_line (product) JOIN fulfillment (DELIVERED)
    // JOIN sales_order (org, customer) — goods in hand, not merely placed or paid (epic §2).
    return dsl.fetchExists(
        dsl.selectOne()
            .from(FULFILLMENT_LINE)
            .join(SALES_ORDER_LINE)
            .on(SALES_ORDER_LINE.ID.eq(FULFILLMENT_LINE.SALES_ORDER_LINE_ID))
            .join(FULFILLMENT)
            .on(FULFILLMENT.ID.eq(FULFILLMENT_LINE.FULFILLMENT_ID))
            .join(SALES_ORDER)
            .on(SALES_ORDER.ID.eq(FULFILLMENT.SALES_ORDER_ID))
            .where(SALES_ORDER_LINE.PRODUCT_ID.eq(productId))
            .and(
                FULFILLMENT.STATUS.eq(
                    com.loai.inventory.repository.generated.enums.FulfillmentStatus.DELIVERED))
            .and(SALES_ORDER.ORG_ID.eq(orgId))
            .and(SALES_ORDER.CUSTOMER_ID.eq(customerId)));
  }

  @Override
  public Optional<ListingReview> findByCustomerAndListing(
      UUID orgId, UUID customerId, UUID listingId) {
    return dsl.selectFrom(LISTING_REVIEW)
        .where(
            LISTING_REVIEW
                .ORG_ID
                .eq(orgId)
                .and(LISTING_REVIEW.CUSTOMER_ID.eq(customerId))
                .and(LISTING_REVIEW.PRODUCT_LISTING_ID.eq(listingId)))
        .fetchOptional()
        .map(this::toReview);
  }

  @Override
  public ListingReview insert(ListingReview r) {
    ListingReviewRecord record =
        dsl.insertInto(LISTING_REVIEW)
            .set(LISTING_REVIEW.ORG_ID, r.getOrgId())
            .set(LISTING_REVIEW.PRODUCT_LISTING_ID, r.getProductListingId())
            .set(LISTING_REVIEW.CUSTOMER_ID, r.getCustomerId())
            .set(LISTING_REVIEW.RATING, (short) r.getRating())
            .set(LISTING_REVIEW.BODY, r.getBody())
            .set(LISTING_REVIEW.DISPLAY_NAME, r.getDisplayName())
            .set(LISTING_REVIEW.STATUS, ReviewStatus.PENDING.name())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into listing_review returned no record");
    }
    return toReview(record);
  }

  @Override
  public ListingReview updateContent(ListingReview r) {
    ListingReviewRecord record =
        dsl.update(LISTING_REVIEW)
            .set(LISTING_REVIEW.RATING, (short) r.getRating())
            .set(LISTING_REVIEW.BODY, r.getBody())
            .set(LISTING_REVIEW.DISPLAY_NAME, r.getDisplayName())
            .set(LISTING_REVIEW.STATUS, ReviewStatus.PENDING.name())
            // No trigger convention in this schema — the upsert bumps updated_at explicitly.
            .set(LISTING_REVIEW.UPDATED_AT, OffsetDateTime.now())
            .where(LISTING_REVIEW.ID.eq(r.getId()))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("UPDATE of listing_review " + r.getId() + " matched no row");
    }
    return toReview(record);
  }

  @Override
  public List<MyReview> findMine(UUID orgId, UUID customerId) {
    return dsl.select(LISTING_REVIEW.fields())
        .select(PRODUCT_LISTING.SLUG, DEFAULT_T.TITLE)
        .from(LISTING_REVIEW)
        .join(PRODUCT_LISTING)
        .on(PRODUCT_LISTING.ID.eq(LISTING_REVIEW.PRODUCT_LISTING_ID))
        .join(ORG)
        .on(ORG.ID.eq(LISTING_REVIEW.ORG_ID))
        .leftJoin(DEFAULT_T)
        .on(
            DEFAULT_T
                .LISTING_ID
                .eq(PRODUCT_LISTING.ID)
                .and(DEFAULT_T.LANGUAGE.eq(ORG.DEFAULT_LOCALE)))
        .where(LISTING_REVIEW.ORG_ID.eq(orgId).and(LISTING_REVIEW.CUSTOMER_ID.eq(customerId)))
        .orderBy(LISTING_REVIEW.CREATED_AT.desc(), LISTING_REVIEW.ID.desc())
        .fetch(
            rec ->
                new MyReview(
                    toReview(rec.into(LISTING_REVIEW)),
                    rec.get(PRODUCT_LISTING.SLUG),
                    rec.get(DEFAULT_T.TITLE)));
  }

  @Override
  public int deleteOwn(UUID orgId, UUID customerId, UUID reviewId) {
    return dsl.deleteFrom(LISTING_REVIEW)
        .where(
            LISTING_REVIEW
                .ORG_ID
                .eq(orgId)
                .and(LISTING_REVIEW.CUSTOMER_ID.eq(customerId))
                .and(LISTING_REVIEW.ID.eq(reviewId)))
        .execute();
  }

  @Override
  public Optional<ListingReview> findById(UUID orgId, UUID reviewId) {
    return dsl.selectFrom(LISTING_REVIEW)
        .where(LISTING_REVIEW.ORG_ID.eq(orgId).and(LISTING_REVIEW.ID.eq(reviewId)))
        .fetchOptional()
        .map(this::toReview);
  }

  @Override
  public int updateStatus(UUID orgId, UUID reviewId, ReviewStatus status) {
    return dsl.update(LISTING_REVIEW)
        .set(LISTING_REVIEW.STATUS, status.name())
        .set(LISTING_REVIEW.UPDATED_AT, OffsetDateTime.now())
        .where(LISTING_REVIEW.ORG_ID.eq(orgId).and(LISTING_REVIEW.ID.eq(reviewId)))
        .execute();
  }

  @Override
  public List<AdminReview> findAdminPage(UUID orgId, ReviewStatus status, int offset, int limit) {
    var query =
        dsl.select(LISTING_REVIEW.fields())
            .select(CUSTOMER.NAME, CUSTOMER.EMAIL, DEFAULT_T.TITLE)
            .from(LISTING_REVIEW)
            .join(CUSTOMER)
            .on(CUSTOMER.ID.eq(LISTING_REVIEW.CUSTOMER_ID))
            .join(PRODUCT_LISTING)
            .on(PRODUCT_LISTING.ID.eq(LISTING_REVIEW.PRODUCT_LISTING_ID))
            .join(ORG)
            .on(ORG.ID.eq(LISTING_REVIEW.ORG_ID))
            .leftJoin(DEFAULT_T)
            .on(
                DEFAULT_T
                    .LISTING_ID
                    .eq(PRODUCT_LISTING.ID)
                    .and(DEFAULT_T.LANGUAGE.eq(ORG.DEFAULT_LOCALE)))
            .where(LISTING_REVIEW.ORG_ID.eq(orgId));
    if (status != null) {
      query = query.and(LISTING_REVIEW.STATUS.eq(status.name()));
    }
    // Filtered = queue view, oldest first (FIFO worklist); unfiltered = ledger, newest first.
    var ordered =
        status != null
            ? query.orderBy(LISTING_REVIEW.CREATED_AT.asc(), LISTING_REVIEW.ID.asc())
            : query.orderBy(LISTING_REVIEW.CREATED_AT.desc(), LISTING_REVIEW.ID.desc());
    return ordered
        .offset(offset)
        .limit(limit)
        .fetch(
            rec ->
                new AdminReview(
                    toReview(rec.into(LISTING_REVIEW)),
                    rec.get(CUSTOMER.NAME),
                    rec.get(CUSTOMER.EMAIL),
                    rec.get(DEFAULT_T.TITLE)));
  }

  @Override
  public long countAdmin(UUID orgId, ReviewStatus status) {
    var condition = LISTING_REVIEW.ORG_ID.eq(orgId);
    if (status != null) {
      condition = condition.and(LISTING_REVIEW.STATUS.eq(status.name()));
    }
    return dsl.fetchCount(dsl.selectOne().from(LISTING_REVIEW).where(condition));
  }

  @Override
  public List<ListingReview> findApprovedPage(UUID orgId, UUID listingId, int offset, int limit) {
    return dsl.selectFrom(LISTING_REVIEW)
        .where(
            LISTING_REVIEW
                .ORG_ID
                .eq(orgId)
                .and(LISTING_REVIEW.PRODUCT_LISTING_ID.eq(listingId))
                .and(LISTING_REVIEW.STATUS.eq(ReviewStatus.APPROVED.name())))
        .orderBy(LISTING_REVIEW.CREATED_AT.desc(), LISTING_REVIEW.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toReview);
  }

  @Override
  public long countApproved(UUID orgId, UUID listingId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(LISTING_REVIEW)
            .where(
                LISTING_REVIEW
                    .ORG_ID
                    .eq(orgId)
                    .and(LISTING_REVIEW.PRODUCT_LISTING_ID.eq(listingId))
                    .and(LISTING_REVIEW.STATUS.eq(ReviewStatus.APPROVED.name()))));
  }

  @Override
  public Map<UUID, Aggregate> findAggregates(UUID orgId, Collection<UUID> listingIds) {
    if (listingIds == null || listingIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Aggregate> out = new HashMap<>();
    for (Record3<UUID, BigDecimal, Integer> rec :
        dsl.select(LISTING_REVIEW.PRODUCT_LISTING_ID, DSL.avg(LISTING_REVIEW.RATING), DSL.count())
            .from(LISTING_REVIEW)
            .where(
                LISTING_REVIEW
                    .ORG_ID
                    .eq(orgId)
                    .and(LISTING_REVIEW.PRODUCT_LISTING_ID.in(listingIds))
                    .and(LISTING_REVIEW.STATUS.eq(ReviewStatus.APPROVED.name())))
            .groupBy(LISTING_REVIEW.PRODUCT_LISTING_ID)
            .fetch()) {
      out.put(rec.value1(), new Aggregate(rec.value2(), rec.value3().longValue()));
    }
    return out;
  }

  private ListingReview toReview(ListingReviewRecord r) {
    return new ListingReview(
        r.getId(),
        r.getOrgId(),
        r.getProductListingId(),
        r.getCustomerId(),
        r.getRating() == null ? 0 : r.getRating().intValue(),
        r.getBody(),
        r.getDisplayName(),
        ReviewStatus.valueOf(r.getStatus()),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
