package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.BannerTargetType;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.StorefrontBanner;
import com.loai.inventory.domain.model.StorefrontBannerTranslation;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontBannerRepository;
import com.loai.inventory.domain.repository.StorefrontBannerRepositoryFactory;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin-plane editor for merchant home banners (customization epic slice C1). Owns the CRUD +
 * reorder + image-presign surface and every write-time rule: the org's default-locale headline is
 * required (epic §1); the target must be a {@link BannerTargetType} slug that resolves <b>within
 * the org</b> at write time (epic §2); an image key must carry the {@code {orgId}/banner/} prefix
 * (epic §4, cross-tenant guard); at most {@value #MAX_ACTIVE_BANNERS} active banners (epic §10);
 * and the display window is coherent ({@code starts_at < ends_at}). The anonymous <b>read</b> lives
 * on {@link StorefrontService#banners} — this class is the write side only. See {@code
 * stories/storefront_banners.md}.
 */
public class StorefrontBannerService {

  private static final Logger log = LoggerFactory.getLogger(StorefrontBannerService.class);

  /** Explicit cap (epic §10) — creating/activating beyond it is a cause-naming 400. */
  static final int MAX_ACTIVE_BANNERS = 10;

  private final DSLContext rootDsl;
  private final StorefrontBannerRepositoryFactory bannerRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final CategoryRepositoryFactory categoryRepoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final ObjectStorage storage;

  public StorefrontBannerService(
      DSLContext rootDsl,
      StorefrontBannerRepositoryFactory bannerRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      CategoryRepositoryFactory categoryRepoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      ObjectStorage storage) {
    this.rootDsl = rootDsl;
    this.bannerRepoFactory = bannerRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.categoryRepoFactory = categoryRepoFactory;
    this.storage = storage;
  }

  /**
   * The mutable content of a banner. On <b>create</b> the target is required; on <b>update</b> a
   * {@code null} field leaves the stored value unchanged (merge, like {@code PUT /orgs}) — a blank
   * string clears a nullable text field, and the target type/slug move together. Clearing a
   * previously-set window bound is not a v1 concern (epic — scheduling beyond one window is
   * out-of-scope).
   */
  public record BannerInput(
      String headlineAr,
      String headlineEn,
      String subheadingAr,
      String subheadingEn,
      String imageObjectKey,
      String targetType,
      String targetSlug,
      Boolean active,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt) {}

  /** A banner plus its presigned preview image URL (null when it has no image). */
  public record BannerView(StorefrontBanner banner, String imageUrl) {}

  /** A presigned banner-image upload: where to PUT, and the key to attach afterward. */
  public record PresignResult(String uploadUrl, String objectKey, long expiresInSeconds) {}

  // reads (admin: all rows, incl. inactive/out-of-window)

  public List<BannerView> list(UUID orgId) {
    StorefrontBannerRepository repo = bannerRepoFactory.create(rootDsl);
    return repo.findAllByOrg(orgId).stream().map(this::toView).toList();
  }

  // writes

  public BannerView create(UUID orgId, BannerInput in) {
    if (in == null) {
      throw new ValidationException("request body is required");
    }
    if (in.targetType() == null || in.targetSlug() == null || in.targetSlug().isBlank()) {
      throw new ValidationException("target_type and target_slug are required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          StorefrontBannerRepository repo = bannerRepoFactory.create(tx);
          String defaultLocale = defaultLocale(orgId, tx);

          StorefrontBanner b = new StorefrontBanner();
          b.setOrgId(orgId);
          b.setHeadlineAr(Text.normalizeText(in.headlineAr()));
          b.setHeadlineEn(Text.normalizeText(in.headlineEn()));
          b.setSubheadingAr(Text.normalizeText(in.subheadingAr()));
          b.setSubheadingEn(Text.normalizeText(in.subheadingEn()));
          b.setImageObjectKey(blankToNull(in.imageObjectKey()));
          applyTarget(b, in.targetType(), in.targetSlug());
          b.setActive(in.active() == null || in.active());
          b.setStartsAt(in.startsAt());
          b.setEndsAt(in.endsAt());
          b.setSortOrder(repo.maxSortOrder(orgId) + 1);

          validate(orgId, b, defaultLocale, tx);
          // Cap: a fresh active banner counts against the limit (a DRAFT-inactive one does not).
          if (b.isActive() && repo.countActive(orgId) >= MAX_ACTIVE_BANNERS) {
            throw new ValidationException(
                "an org may have at most " + MAX_ACTIVE_BANNERS + " active banners");
          }

          StorefrontBanner saved = repo.insert(b);
          // The insert RETURNING no longer carries the paired headline/subheading columns (dropped
          // at L6); carry them from the input-built banner so translationRows + the view see them.
          carryPairedContent(b, saved);
          // Per-language rows are the authoritative store now (L6). The default-locale row is
          // guaranteed non-empty by the headline rule above.
          repo.replaceTranslations(saved.getId(), translationRows(saved));
          BannerView view = toView(saved);
          log.info("Created storefront_banner id={} orgId={}", view.banner().getId(), orgId);
          return view;
        });
  }

  public BannerView update(UUID orgId, UUID id, BannerInput in) {
    if (in == null) {
      throw new ValidationException("request body is required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          StorefrontBannerRepository repo = bannerRepoFactory.create(tx);
          StorefrontBanner b =
              repo.findById(orgId, id)
                  .orElseThrow(() -> new NotFoundException("StorefrontBanner", id));
          boolean wasActive = b.isActive();
          String defaultLocale = defaultLocale(orgId, tx);

          // Merge: a null field is left unchanged; a non-null text field applies (blank clears).
          if (in.headlineAr() != null) b.setHeadlineAr(Text.normalizeText(in.headlineAr()));
          if (in.headlineEn() != null) b.setHeadlineEn(Text.normalizeText(in.headlineEn()));
          if (in.subheadingAr() != null) b.setSubheadingAr(Text.normalizeText(in.subheadingAr()));
          if (in.subheadingEn() != null) b.setSubheadingEn(Text.normalizeText(in.subheadingEn()));
          if (in.imageObjectKey() != null) b.setImageObjectKey(blankToNull(in.imageObjectKey()));
          if (in.targetType() != null || in.targetSlug() != null) {
            if (in.targetType() == null || in.targetSlug() == null || in.targetSlug().isBlank()) {
              throw new ValidationException(
                  "target_type and target_slug must be provided together");
            }
            applyTarget(b, in.targetType(), in.targetSlug());
          }
          if (in.active() != null) b.setActive(in.active());
          if (in.startsAt() != null) b.setStartsAt(in.startsAt());
          if (in.endsAt() != null) b.setEndsAt(in.endsAt());

          validate(orgId, b, defaultLocale, tx);
          // Cap only on an inactive→active transition (an already-active row is in the count).
          if (b.isActive() && !wasActive && repo.countActive(orgId) >= MAX_ACTIVE_BANNERS) {
            throw new ValidationException(
                "an org may have at most " + MAX_ACTIVE_BANNERS + " active banners");
          }

          StorefrontBanner saved = repo.update(b);
          carryPairedContent(b, saved); // RETURNING lost the paired columns at L6
          repo.replaceTranslations(saved.getId(), translationRows(saved));
          BannerView view = toView(saved);
          log.info("Updated storefront_banner id={} orgId={}", id, orgId);
          return view;
        });
  }

  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          StorefrontBannerRepository repo = bannerRepoFactory.create(DSL.using(cfg));
          repo.deleteById(orgId, id);
          log.info("Deleted storefront_banner id={} orgId={}", id, orgId);
        });
  }

  /**
   * Set-replace the org's banner ordering ({@code sort_order = position}). The incoming {@code ids}
   * must be exactly the org's banner set — no duplicates, none foreign, none missing (a partial or
   * cross-org list → 400) — and the whole rewrite is one transaction (atomic).
   */
  public List<BannerView> reorder(UUID orgId, List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new ValidationException("ids is required");
    }
    Set<UUID> unique = new LinkedHashSet<>(ids);
    if (unique.size() != ids.size()) {
      throw new ValidationException("ids must not contain duplicates");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          StorefrontBannerRepository repo = bannerRepoFactory.create(tx);
          Set<UUID> orgIds = new HashSet<>(repo.findIdsByOrg(orgId));
          if (!orgIds.equals(unique)) {
            throw new ValidationException(
                "ids must be exactly this org's banners (a full reorder of every banner)");
          }
          int position = 0;
          for (UUID id : ids) {
            repo.updateSortOrder(orgId, id, position++);
          }
          log.info("Reordered {} storefront_banners orgId={}", ids.size(), orgId);
          return repo.findAllByOrg(orgId).stream().map(this::toView).toList();
        });
  }

  /**
   * Hand out a presigned PUT URL + an org-scoped {@code {orgId}/banner/…} object key. No row is
   * written — the client uploads bytes, then sets {@code image_object_key} via create/update (which
   * re-checks the prefix). Mirrors the logo / listing-image presign machinery.
   */
  public PresignResult presignImageUpload(UUID orgId, String filename, String contentType) {
    if (filename == null || filename.isBlank()) {
      throw new ValidationException("filename is required");
    }
    orgRepoFactory
        .create(rootDsl)
        .findById(orgId)
        .orElseThrow(() -> new NotFoundException("Org", orgId));
    String objectKey = storage.newBannerKey(orgId, filename);
    return new PresignResult(
        storage.presignPut(objectKey, contentType), objectKey, storage.presignTtlSeconds());
  }

  // helpers

  private void applyTarget(StorefrontBanner b, String type, String slug) {
    BannerTargetType t;
    try {
      t = BannerTargetType.fromWire(type);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("target_type must be 'category' or 'listing'");
    }
    b.setTargetType(t);
    b.setTargetSlug(slug.trim());
  }

  /**
   * The write-time invariants shared by create and update: default-locale headline present, image
   * key org-owned, window coherent, and the target slug resolving within the org (an existing
   * category, or a listing in any status — a currently-unpublished listing is storable but won't
   * serve; that's the read's job).
   */
  private void validate(UUID orgId, StorefrontBanner b, String defaultLocale, DSLContext tx) {
    String requiredHeadline = "en".equals(defaultLocale) ? b.getHeadlineEn() : b.getHeadlineAr();
    if (requiredHeadline == null || requiredHeadline.isBlank()) {
      throw new ValidationException(
          "a headline in the org's default locale (" + defaultLocale + ") is required");
    }
    if (b.getImageObjectKey() != null
        && !b.getImageObjectKey().startsWith(ObjectStorage.bannerKeyPrefix(orgId))) {
      throw new ValidationException("image_object_key does not belong to this org");
    }
    if (b.getStartsAt() != null
        && b.getEndsAt() != null
        && !b.getStartsAt().isBefore(b.getEndsAt())) {
      throw new ValidationException("starts_at must be before ends_at");
    }
    boolean resolves =
        switch (b.getTargetType()) {
          case CATEGORY -> categoryRepoFactory.create(tx).existsBySlug(orgId, b.getTargetSlug());
          case LISTING -> listingRepoFactory.create(tx).existsBySlug(orgId, b.getTargetSlug());
        };
    if (!resolves) {
      throw new ValidationException(
          "target_slug does not resolve to a "
              + b.getTargetType().wire()
              + " in this org: "
              + b.getTargetSlug());
    }
  }

  private String defaultLocale(UUID orgId, DSLContext tx) {
    Org org =
        orgRepoFactory
            .create(tx)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));
    String loc = org.getDefaultLocale();
    return loc == null || loc.isBlank() ? "ar" : loc.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Carry the paired headline/subheading sides across (the write's input → the returned banner).
   */
  private static void carryPairedContent(StorefrontBanner from, StorefrontBanner to) {
    to.setHeadlineAr(from.getHeadlineAr());
    to.setHeadlineEn(from.getHeadlineEn());
    to.setSubheadingAr(from.getSubheadingAr());
    to.setSubheadingEn(from.getSubheadingEn());
  }

  /**
   * The per-language rows to persist from a banner's paired content (slice L4): one row per
   * non-empty locale side, reproducing the L1 backfill exactly (headline OR subheading present).
   */
  private static List<StorefrontBannerTranslation> translationRows(StorefrontBanner b) {
    List<StorefrontBannerTranslation> rows = new java.util.ArrayList<>(2);
    if (b.getHeadlineAr() != null || b.getSubheadingAr() != null) {
      rows.add(new StorefrontBannerTranslation("ar", b.getHeadlineAr(), b.getSubheadingAr()));
    }
    if (b.getHeadlineEn() != null || b.getSubheadingEn() != null) {
      rows.add(new StorefrontBannerTranslation("en", b.getHeadlineEn(), b.getSubheadingEn()));
    }
    return rows;
  }

  private BannerView toView(StorefrontBanner b) {
    String url = b.getImageObjectKey() == null ? null : storage.presignGet(b.getImageObjectKey());
    return new BannerView(b, url);
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
