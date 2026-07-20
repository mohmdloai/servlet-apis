package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.STOREFRONT_BANNER;
import static com.loai.inventory.repository.generated.Tables.STOREFRONT_BANNER_TRANSLATION;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.BannerTargetType;
import com.loai.inventory.domain.model.StorefrontBanner;
import com.loai.inventory.domain.model.StorefrontBannerTranslation;
import com.loai.inventory.domain.repository.StorefrontBannerRepository;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.repository.generated.tables.records.StorefrontBannerRecord;
import com.loai.inventory.repository.generated.tables.records.StorefrontBannerTranslationRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    List<StorefrontBanner> rows =
        dsl.selectFrom(STOREFRONT_BANNER)
            .where(STOREFRONT_BANNER.ORG_ID.eq(orgId))
            .orderBy(STOREFRONT_BANNER.SORT_ORDER.asc(), STOREFRONT_BANNER.CREATED_AT.asc())
            .fetch()
            .map(this::toBanner);
    loadPairedTranslations(rows);
    return rows;
  }

  @Override
  public Optional<StorefrontBanner> findById(UUID orgId, UUID id) {
    Optional<StorefrontBanner> found =
        dsl.selectFrom(STOREFRONT_BANNER)
            .where(STOREFRONT_BANNER.ORG_ID.eq(orgId).and(STOREFRONT_BANNER.ID.eq(id)))
            .fetchOptional()
            .map(this::toBanner);
    found.ifPresent(b -> loadPairedTranslations(List.of(b)));
    return found;
  }

  @Override
  public StorefrontBanner insert(StorefrontBanner b) {
    StorefrontBannerRecord record =
        dsl.insertInto(STOREFRONT_BANNER)
            .set(STOREFRONT_BANNER.ORG_ID, b.getOrgId())
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

    List<StorefrontBanner> rows =
        dsl.selectFrom(STOREFRONT_BANNER)
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
    loadPairedTranslations(rows);
    return rows;
  }

  /**
   * Fill each banner's paired {@code headlineAr/En}/{@code subheadingAr/En} from its per-language
   * translation rows (L6 — the legacy paired columns are gone, so the admin-plane view and the
   * public resolver's default-locale pick are sourced from {@code storefront_banner_translation}).
   * One batched query for the whole set; a banner with no rows keeps null sides.
   */
  private void loadPairedTranslations(List<StorefrontBanner> banners) {
    if (banners.isEmpty()) {
      return;
    }
    Map<UUID, List<StorefrontBannerTranslation>> byBanner =
        findTranslationsForBanners(banners.stream().map(StorefrontBanner::getId).toList());
    for (StorefrontBanner b : banners) {
      for (StorefrontBannerTranslation t : byBanner.getOrDefault(b.getId(), List.of())) {
        if ("ar".equals(t.language())) {
          b.setHeadlineAr(t.headline());
          b.setSubheadingAr(t.subheading());
        } else if ("en".equals(t.language())) {
          b.setHeadlineEn(t.headline());
          b.setSubheadingEn(t.subheading());
        }
      }
    }
  }

  // --- translations (content-localization slice L4) ---

  @Override
  public void replaceTranslations(UUID bannerId, List<StorefrontBannerTranslation> translations) {
    dsl.deleteFrom(STOREFRONT_BANNER_TRANSLATION)
        .where(STOREFRONT_BANNER_TRANSLATION.BANNER_ID.eq(bannerId))
        .execute();
    if (translations == null || translations.isEmpty()) {
      return;
    }
    List<StorefrontBannerTranslationRecord> rows = new ArrayList<>(translations.size());
    for (StorefrontBannerTranslation t : translations) {
      StorefrontBannerTranslationRecord r = dsl.newRecord(STOREFRONT_BANNER_TRANSLATION);
      r.setBannerId(bannerId);
      r.setLanguage(t.language());
      r.setHeadline(t.headline());
      r.setSubheading(t.subheading());
      rows.add(r);
    }
    dsl.batchInsert(rows).execute();
  }

  @Override
  public Map<UUID, List<StorefrontBannerTranslation>> findTranslationsForBanners(
      Collection<UUID> bannerIds) {
    if (bannerIds == null || bannerIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<StorefrontBannerTranslation>> byBanner = new HashMap<>();
    dsl.selectFrom(STOREFRONT_BANNER_TRANSLATION)
        .where(STOREFRONT_BANNER_TRANSLATION.BANNER_ID.in(bannerIds))
        .orderBy(
            STOREFRONT_BANNER_TRANSLATION.BANNER_ID.asc(),
            STOREFRONT_BANNER_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .forEach(
            r ->
                byBanner
                    .computeIfAbsent(r.getBannerId(), k -> new ArrayList<>())
                    .add(
                        new StorefrontBannerTranslation(
                            r.getLanguage(), r.getHeadline(), r.getSubheading())));
    return byBanner;
  }

  /**
   * Map a banner row. The paired {@code headline/subheading} sides are NOT read here since L6
   * dropped the legacy columns — {@link #loadPairedTranslations} fills them from the translation
   * table on the read paths, and the write paths carry them from the input (see {@code
   * StorefrontBannerService}). A bare RETURNING record therefore maps with null sides.
   */
  private StorefrontBanner toBanner(StorefrontBannerRecord r) {
    StorefrontBanner b = new StorefrontBanner();
    b.setId(r.getId());
    b.setOrgId(r.getOrgId());
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
