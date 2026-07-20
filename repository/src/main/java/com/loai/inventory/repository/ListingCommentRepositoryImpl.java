package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.LISTING_COMMENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;

import com.loai.inventory.domain.model.CommentStatus;
import com.loai.inventory.domain.model.ListingComment;
import com.loai.inventory.domain.repository.ListingCommentRepository;
import com.loai.inventory.repository.generated.tables.records.ListingCommentRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class ListingCommentRepositoryImpl implements ListingCommentRepository {

  private final DSLContext dsl;

  /**
   * The listing's default-locale title, aliased for the worklist reads. Since L6 dropped {@code
   * product_listing.title}, the merchant-facing display title (My questions, the moderation queue)
   * is resolved from the {@code product_listing_translation} row whose {@code language} equals the
   * org's {@code default_locale} (NOT NULL since V52; a default-locale row is guaranteed).
   */
  private static final com.loai.inventory.repository.generated.tables.ProductListingTranslation
      DEFAULT_T = PRODUCT_LISTING_TRANSLATION.as("default_t");

  public ListingCommentRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public long countPending(UUID orgId, UUID customerId, UUID listingId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(LISTING_COMMENT)
            .where(
                LISTING_COMMENT
                    .ORG_ID
                    .eq(orgId)
                    .and(LISTING_COMMENT.CUSTOMER_ID.eq(customerId))
                    .and(LISTING_COMMENT.PRODUCT_LISTING_ID.eq(listingId))
                    .and(LISTING_COMMENT.STATUS.eq(CommentStatus.PENDING.name()))));
  }

  @Override
  public ListingComment insert(ListingComment c) {
    ListingCommentRecord record =
        dsl.insertInto(LISTING_COMMENT)
            .set(LISTING_COMMENT.ORG_ID, c.getOrgId())
            .set(LISTING_COMMENT.PRODUCT_LISTING_ID, c.getProductListingId())
            .set(LISTING_COMMENT.CUSTOMER_ID, c.getCustomerId())
            .set(LISTING_COMMENT.BODY, c.getBody())
            .set(LISTING_COMMENT.DISPLAY_NAME, c.getDisplayName())
            .set(LISTING_COMMENT.STATUS, CommentStatus.PENDING.name())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into listing_comment returned no record");
    }
    return toComment(record);
  }

  @Override
  public List<MyComment> findMine(UUID orgId, UUID customerId) {
    return dsl.select(LISTING_COMMENT.fields())
        .select(PRODUCT_LISTING.SLUG, DEFAULT_T.TITLE)
        .from(LISTING_COMMENT)
        .join(PRODUCT_LISTING)
        .on(PRODUCT_LISTING.ID.eq(LISTING_COMMENT.PRODUCT_LISTING_ID))
        .join(ORG)
        .on(ORG.ID.eq(LISTING_COMMENT.ORG_ID))
        .leftJoin(DEFAULT_T)
        .on(
            DEFAULT_T
                .LISTING_ID
                .eq(PRODUCT_LISTING.ID)
                .and(DEFAULT_T.LANGUAGE.eq(ORG.DEFAULT_LOCALE)))
        .where(LISTING_COMMENT.ORG_ID.eq(orgId).and(LISTING_COMMENT.CUSTOMER_ID.eq(customerId)))
        .orderBy(LISTING_COMMENT.CREATED_AT.desc(), LISTING_COMMENT.ID.desc())
        .fetch(
            rec ->
                new MyComment(
                    toComment(rec.into(LISTING_COMMENT)),
                    rec.get(PRODUCT_LISTING.SLUG),
                    rec.get(DEFAULT_T.TITLE)));
  }

  @Override
  public int deleteOwn(UUID orgId, UUID customerId, UUID commentId) {
    return dsl.deleteFrom(LISTING_COMMENT)
        .where(
            LISTING_COMMENT
                .ORG_ID
                .eq(orgId)
                .and(LISTING_COMMENT.CUSTOMER_ID.eq(customerId))
                .and(LISTING_COMMENT.ID.eq(commentId)))
        .execute();
  }

  @Override
  public Optional<ListingComment> findById(UUID orgId, UUID commentId) {
    return dsl.selectFrom(LISTING_COMMENT)
        .where(LISTING_COMMENT.ORG_ID.eq(orgId).and(LISTING_COMMENT.ID.eq(commentId)))
        .fetchOptional()
        .map(this::toComment);
  }

  @Override
  public ListingComment updateReply(ListingComment c) {
    ListingCommentRecord record =
        dsl.update(LISTING_COMMENT)
            .set(LISTING_COMMENT.REPLY_BODY, c.getReplyBody())
            .set(LISTING_COMMENT.REPLIED_BY, c.getRepliedBy())
            .set(LISTING_COMMENT.REPLIED_AT, c.getRepliedAt())
            .set(LISTING_COMMENT.STATUS, CommentStatus.ANSWERED.name())
            .where(LISTING_COMMENT.ID.eq(c.getId()))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("UPDATE of listing_comment " + c.getId() + " matched no row");
    }
    return toComment(record);
  }

  @Override
  public int updateStatus(UUID orgId, UUID commentId, CommentStatus status) {
    return dsl.update(LISTING_COMMENT)
        .set(LISTING_COMMENT.STATUS, status.name())
        .where(LISTING_COMMENT.ORG_ID.eq(orgId).and(LISTING_COMMENT.ID.eq(commentId)))
        .execute();
  }

  @Override
  public List<AdminComment> findAdminPage(UUID orgId, CommentStatus status, int offset, int limit) {
    var query =
        dsl.select(LISTING_COMMENT.fields())
            .select(CUSTOMER.NAME, CUSTOMER.EMAIL, DEFAULT_T.TITLE)
            .from(LISTING_COMMENT)
            .join(CUSTOMER)
            .on(CUSTOMER.ID.eq(LISTING_COMMENT.CUSTOMER_ID))
            .join(PRODUCT_LISTING)
            .on(PRODUCT_LISTING.ID.eq(LISTING_COMMENT.PRODUCT_LISTING_ID))
            .join(ORG)
            .on(ORG.ID.eq(LISTING_COMMENT.ORG_ID))
            .leftJoin(DEFAULT_T)
            .on(
                DEFAULT_T
                    .LISTING_ID
                    .eq(PRODUCT_LISTING.ID)
                    .and(DEFAULT_T.LANGUAGE.eq(ORG.DEFAULT_LOCALE)))
            .where(LISTING_COMMENT.ORG_ID.eq(orgId));
    if (status != null) {
      query = query.and(LISTING_COMMENT.STATUS.eq(status.name()));
    }
    // Filtered = queue view, oldest first (FIFO worklist); unfiltered = ledger, newest first.
    var ordered =
        status != null
            ? query.orderBy(LISTING_COMMENT.CREATED_AT.asc(), LISTING_COMMENT.ID.asc())
            : query.orderBy(LISTING_COMMENT.CREATED_AT.desc(), LISTING_COMMENT.ID.desc());
    return ordered
        .offset(offset)
        .limit(limit)
        .fetch(
            rec ->
                new AdminComment(
                    toComment(rec.into(LISTING_COMMENT)),
                    rec.get(CUSTOMER.NAME),
                    rec.get(CUSTOMER.EMAIL),
                    rec.get(DEFAULT_T.TITLE)));
  }

  @Override
  public long countAdmin(UUID orgId, CommentStatus status) {
    var condition = LISTING_COMMENT.ORG_ID.eq(orgId);
    if (status != null) {
      condition = condition.and(LISTING_COMMENT.STATUS.eq(status.name()));
    }
    return dsl.fetchCount(dsl.selectOne().from(LISTING_COMMENT).where(condition));
  }

  @Override
  public List<ListingComment> findAnsweredPage(UUID orgId, UUID listingId, int offset, int limit) {
    return dsl.selectFrom(LISTING_COMMENT)
        .where(
            LISTING_COMMENT
                .ORG_ID
                .eq(orgId)
                .and(LISTING_COMMENT.PRODUCT_LISTING_ID.eq(listingId))
                .and(LISTING_COMMENT.STATUS.eq(CommentStatus.ANSWERED.name())))
        .orderBy(LISTING_COMMENT.CREATED_AT.desc(), LISTING_COMMENT.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toComment);
  }

  @Override
  public long countAnswered(UUID orgId, UUID listingId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(LISTING_COMMENT)
            .where(
                LISTING_COMMENT
                    .ORG_ID
                    .eq(orgId)
                    .and(LISTING_COMMENT.PRODUCT_LISTING_ID.eq(listingId))
                    .and(LISTING_COMMENT.STATUS.eq(CommentStatus.ANSWERED.name()))));
  }

  private ListingComment toComment(ListingCommentRecord r) {
    return new ListingComment(
        r.getId(),
        r.getOrgId(),
        r.getProductListingId(),
        r.getCustomerId(),
        r.getBody(),
        r.getDisplayName(),
        r.getReplyBody(),
        r.getRepliedBy(),
        r.getRepliedAt(),
        CommentStatus.valueOf(r.getStatus()),
        r.getCreatedAt());
  }
}
