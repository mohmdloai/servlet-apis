package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.StorefrontBanner;
import com.loai.inventory.domain.model.StorefrontBannerTranslation;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link StorefrontBanner} (customization epic slice C1). The admin reads/writes
 * are org-scoped; {@link #findPublicResolved} is the anonymous storefront read, which JOIN-filters
 * out any banner whose target no longer resolves (epic §3 — degradation lives here, not in the
 * client).
 */
public interface StorefrontBannerRepository {

  /** Every banner of the org (active + inactive + out-of-window), {@code sort_order ASC}. */
  List<StorefrontBanner> findAllByOrg(UUID orgId);

  Optional<StorefrontBanner> findById(UUID orgId, UUID id);

  StorefrontBanner insert(StorefrontBanner banner);

  /** Full field update of an existing row (all mutable columns + {@code updated_at}). */
  StorefrontBanner update(StorefrontBanner banner);

  void deleteById(UUID orgId, UUID id);

  /** Count of the org's currently-active banners (the ≤10-active cap guard). */
  int countActive(UUID orgId);

  /** The org's highest {@code sort_order}, or -1 when it has no banners (append = max+1). */
  int maxSortOrder(UUID orgId);

  /**
   * Every banner id of the org (the reorder set-replace validates the incoming ids against this).
   */
  List<UUID> findIdsByOrg(UUID orgId);

  /** Set one banner's {@code sort_order} (org-scoped; used by the atomic reorder set-replace). */
  void updateSortOrder(UUID orgId, UUID id, int sortOrder);

  /**
   * The anonymous storefront read: the org's banners that are {@code active}, inside their window
   * ({@code now} between the null-open bounds), <b>and whose target still resolves</b> — a {@code
   * category} target joins an existing category, a {@code listing} target joins a <b>PUBLISHED</b>
   * listing. {@code sort_order ASC}. A banner pointing at an unpublished/deleted target simply does
   * not appear (and reappears when the target is restored).
   */
  List<StorefrontBanner> findPublicResolved(UUID orgId, OffsetDateTime now);

  // --- translations (content-localization slice L4) ---

  /** Replace the whole per-language translation set for a banner (delete-then-insert). */
  void replaceTranslations(UUID bannerId, List<StorefrontBannerTranslation> translations);

  /** Batch-load translations for a set of banners (avoids N+1), keyed by banner id. */
  Map<UUID, List<StorefrontBannerTranslation>> findTranslationsForBanners(
      Collection<UUID> bannerIds);
}
