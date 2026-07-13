package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.STOREFRONT_BANNER;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.BannerTargetType;
import com.loai.inventory.domain.model.StorefrontBanner;
import com.loai.inventory.domain.repository.StorefrontBannerRepository;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.repository.generated.tables.records.StorefrontBannerRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class StorefrontBannerRepositoryImpl implements StorefrontBannerRepository {

  private final DSLContext dsl;

  public StorefrontBannerRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<StorefrontBanner> findAllByOrg(UUID orgId) {
    return dsl.selectFrom(STOREFRONT_BANNER)
        .where(STOREFRONT_BANNER.ORG_ID.eq(orgId))
        .orderBy(STOREFRONT_BANNER.SORT_ORDER.asc(), STOREFRONT_BANNER.CREATED_AT.asc())
        .fetch()
        .map(this::toBanner);
  }

  @Override
  public Optional<StorefrontBanner> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(STOREFRONT_BANNER)
        .where(STOREFRONT_BANNER.ORG_ID.eq(orgId).and(STOREFRONT_BANNER.ID.eq(id)))
        .fetchOptional()
        .map(this::toBanner);
  }

  @Override
  public StorefrontBanner insert(StorefrontBanner b) {
    StorefrontBannerRecord record =
        dsl.insertInto(STOREFRONT_BANNER)
            .set(STOREFRONT_BANNER.ORG_ID, b.getOrgId())
            .set(STOREFRONT_BANNER.HEADLINE_AR, b.getHeadlineAr())
            .set(STOREFRONT_BANNER.HEADLINE_EN, b.getHeadlineEn())
            .set(STOREFRONT_BANNER.SUBHEADING_AR, b.getSubheadingAr())
            .set(STOREFRONT_BANNER.SUBHEADING_EN, b.getSubheadingEn())
            .set(STOREFRONT_BANNER.IMAGE_OBJECT_KEY, b.getImageObjectKey())
            .set(STOREFRONT_BANNER.TARGET_TYPE, b.getTargetType().wire())
            .set(STOREFRONT_BANNER.TARGET_SLUG, b.getTargetSlug())
            .set(STOREFRONT_BANNER.SORT_ORDER, b.getSortOrder())
            .set(STOREFRONT_BANNER.ACTIVE, b.isActive())
            .set(STOREFRONT_BANNER.STARTS_AT, b.getStartsAt())
            .set(STOREFRONT_BANNER.ENDS_AT, b.getEndsAt())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into storefront_banner returned no record");
    }
    return toBanner(record);
  }

  @Override
  public StorefrontBanner update(StorefrontBanner b) {
    StorefrontBannerRecord record =
        dsl.update(STOREFRONT_BANNER)
            .set(STOREFRONT_BANNER.HEADLINE_AR, b.getHeadlineAr())
            .set(STOREFRONT_BANNER.HEADLINE_EN, b.getHeadlineEn())
            .set(STOREFRONT_BANNER.SUBHEADING_AR, b.getSubheadingAr())
            .set(STOREFRONT_BANNER.SUBHEADING_EN, b.getSubheadingEn())
            .set(STOREFRONT_BANNER.IMAGE_OBJECT_KEY, b.getImageObjectKey())
            .set(STOREFRONT_BANNER.TARGET_TYPE, b.getTargetType().wire())
            .set(STOREFRONT_BANNER.TARGET_SLUG, b.getTargetSlug())
            .set(STOREFRONT_BANNER.ACTIVE, b.isActive())
            .set(STOREFRONT_BANNER.STARTS_AT, b.getStartsAt())
            .set(STOREFRONT_BANNER.ENDS_AT, b.getEndsAt())
            .set(STOREFRONT_BANNER.UPDATED_AT, OffsetDateTime.now())
            .where(
                STOREFRONT_BANNER.ORG_ID.eq(b.getOrgId()).and(STOREFRONT_BANNER.ID.eq(b.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("StorefrontBanner", b.getId());
    }
    return toBanner(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(STOREFRONT_BANNER)
            .where(STOREFRONT_BANNER.ORG_ID.eq(orgId).and(STOREFRONT_BANNER.ID.eq(id)))
            .execute();
    if (deleted == 0) {
      throw new NotFoundException("StorefrontBanner", id);
    }
  }

  @Override
  public int countActive(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(STOREFRONT_BANNER)
            .where(STOREFRONT_BANNER.ORG_ID.eq(orgId).and(STOREFRONT_BANNER.ACTIVE.isTrue())));
  }

  @Override
  public int maxSortOrder(UUID orgId) {
    Integer max =
        dsl.select(DSL.max(STOREFRONT_BANNER.SORT_ORDER))
            .from(STOREFRONT_BANNER)
            .where(STOREFRONT_BANNER.ORG_ID.eq(orgId))
            .fetchOne(0, Integer.class);
    return max == null ? -1 : max;
  }

  @Override
  public List<UUID> findIdsByOrg(UUID orgId) {
    return dsl.select(STOREFRONT_BANNER.ID)
        .from(STOREFRONT_BANNER)
        .where(STOREFRONT_BANNER.ORG_ID.eq(orgId))
        .fetch(STOREFRONT_BANNER.ID);
  }

  @Override
  public void updateSortOrder(UUID orgId, UUID id, int sortOrder) {
    dsl.update(STOREFRONT_BANNER)
        .set(STOREFRONT_BANNER.SORT_ORDER, sortOrder)
        .set(STOREFRONT_BANNER.UPDATED_AT, OffsetDateTime.now())
        .where(STOREFRONT_BANNER.ORG_ID.eq(orgId).and(STOREFRONT_BANNER.ID.eq(id)))
        .execute();
  }

  @Override
  public List<StorefrontBanner> findPublicResolved(UUID orgId, OffsetDateTime now) {
    // active ∧ in-window ∧ target-resolves. The window edges are null-open; the resolving check is
    // an EXISTS per target kind so an unpublished/deleted target silently drops the banner (epic
    // §3).
    Condition inWindow =
        STOREFRONT_BANNER
            .STARTS_AT
            .isNull()
            .or(STOREFRONT_BANNER.STARTS_AT.le(now))
            .and(STOREFRONT_BANNER.ENDS_AT.isNull().or(STOREFRONT_BANNER.ENDS_AT.gt(now)));

    Condition categoryResolves =
        STOREFRONT_BANNER
            .TARGET_TYPE
            .eq(BannerTargetType.CATEGORY.wire())
            .and(
                DSL.exists(
                    dsl.selectOne()
                        .from(CATEGORY)
                        .where(
                            CATEGORY
                                .ORG_ID
                                .eq(STOREFRONT_BANNER.ORG_ID)
                                .and(CATEGORY.SLUG.eq(STOREFRONT_BANNER.TARGET_SLUG)))));

    Condition listingResolves =
        STOREFRONT_BANNER
            .TARGET_TYPE
            .eq(BannerTargetType.LISTING.wire())
            .and(
                DSL.exists(
                    dsl.selectOne()
                        .from(PRODUCT_LISTING)
                        .where(
                            PRODUCT_LISTING
                                .ORG_ID
                                .eq(STOREFRONT_BANNER.ORG_ID)
                                .and(PRODUCT_LISTING.SLUG.eq(STOREFRONT_BANNER.TARGET_SLUG))
                                .and(PRODUCT_LISTING.STATUS.eq(ListingStatus.PUBLISHED)))));

    return dsl.selectFrom(STOREFRONT_BANNER)
        .where(
            STOREFRONT_BANNER
                .ORG_ID
                .eq(orgId)
                .and(STOREFRONT_BANNER.ACTIVE.isTrue())
                .and(inWindow)
                .and(categoryResolves.or(listingResolves)))
        .orderBy(STOREFRONT_BANNER.SORT_ORDER.asc(), STOREFRONT_BANNER.CREATED_AT.asc())
        .fetch()
        .map(this::toBanner);
  }

  private StorefrontBanner toBanner(StorefrontBannerRecord r) {
    StorefrontBanner b = new StorefrontBanner();
    b.setId(r.getId());
    b.setOrgId(r.getOrgId());
    b.setHeadlineAr(r.getHeadlineAr());
    b.setHeadlineEn(r.getHeadlineEn());
    b.setSubheadingAr(r.getSubheadingAr());
    b.setSubheadingEn(r.getSubheadingEn());
    b.setImageObjectKey(r.getImageObjectKey());
    b.setTargetType(BannerTargetType.fromWire(r.getTargetType()));
    b.setTargetSlug(r.getTargetSlug());
    b.setSortOrder(r.getSortOrder() == null ? 0 : r.getSortOrder());
    b.setActive(Boolean.TRUE.equals(r.getActive()));
    b.setStartsAt(r.getStartsAt());
    b.setEndsAt(r.getEndsAt());
    b.setCreatedAt(r.getCreatedAt());
    b.setUpdatedAt(r.getUpdatedAt());
    return b;
  }
}
