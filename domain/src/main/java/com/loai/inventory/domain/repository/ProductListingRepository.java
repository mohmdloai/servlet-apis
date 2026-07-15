package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ListingSort;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ProductListingRepository {

  /**
   * One PUBLISHED listing resolved for anonymous checkout: the public {@code slug} mapped to the
   * internal {@code productId} and the published {@code salesPrice} the shopper is charged, plus
   * the display {@code title}. The slug→product mapping never crosses the public boundary — it
   * stays inside {@link #resolveForCheckout}. See {@code stories/public_checkout.md}.
   */
  record CheckoutLineResolution(
      String slug, UUID productId, java.math.BigDecimal salesPrice, String title) {}

  /**
   * Resolve a cart's listing slugs → {@link CheckoutLineResolution} in one query, constrained to
   * the given status (PUBLISHED for checkout) and {@code orgId}. A slug that is unknown or not in
   * {@code status} is simply absent from the result — the caller detects the miss and answers an
   * opaque 404 without distinguishing "no such slug" from "exists but DRAFT/ARCHIVED".
   */
  List<CheckoutLineResolution> resolveForCheckout(
      UUID orgId, Collection<String> slugs, ListingStatus status);

  /**
   * One PUBLISHED listing's advisory availability: the public {@code slug} mapped to its {@code
   * available} quantity ({@code stock_qty - reserved_qty}; untracked/missing inventory → 0), via a
   * LEFT JOIN to {@code inventory}. Slug-keyed — the {@code product_id} never leaves the
   * repository; the service turns {@code available} into the boolean {@code in_stock}. See {@code
   * stories/storefront_availability_signal.md} (B2).
   */
  record ListingAvailability(String slug, int available) {}

  /**
   * Resolve a set of listing slugs → {@link ListingAvailability}, constrained to {@code status}
   * (PUBLISHED) and {@code orgId}, LEFT JOINed to {@code inventory} so an untracked product yields
   * {@code available = 0}. A slug that is unknown or not in {@code status} is absent from the
   * result (the caller maps it to {@code in_stock: false} — never a 404, never an oracle).
   */
  List<ListingAvailability> resolveAvailability(
      UUID orgId, Collection<String> slugs, ListingStatus status);

  /**
   * A past order line resolved back to a currently-buyable listing for reorder (slice P4, {@code
   * portal_addresses_reorder.md}). Keyed by {@code productId} (the order line's only handle) → the
   * public {@code slug} the storefront serves, the display {@code title}, the current {@code
   * salesPrice}, and the advisory {@code available} qty ({@code stock_qty - reserved_qty};
   * untracked/missing inventory → 0). The service turns {@code available} into the boolean {@code
   * in_stock} and never lets {@code productId} cross the customer boundary.
   */
  record ReorderResolution(
      UUID productId, String slug, String title, BigDecimal salesPrice, int available) {}

  /**
   * Resolve a set of {@code productId}s → their current PUBLISHED {@link ReorderResolution} in one
   * query, LEFT JOINed to {@code inventory} for the availability signal, constrained to {@code
   * status} (PUBLISHED) and {@code orgId}. A product with no listing in {@code status} is simply
   * absent from the result — the caller reports it under {@code unavailable}, never a 404. Product
   * ⇄ listing is 1:1 per org (unique index), so at most one row per id.
   */
  List<ReorderResolution> resolveForReorder(
      UUID orgId, Collection<UUID> productIds, ListingStatus status);

  // --- listing CRUD ---

  Optional<ProductListing> findById(UUID orgId, UUID id);

  /** Storefront read: a listing by its public slug, constrained to a status (e.g. PUBLISHED). */
  Optional<ProductListing> findBySlugAndStatus(UUID orgId, String slug, ListingStatus status);

  /**
   * A listing by its public slug in the org, <b>any status</b> — the portal review-write resolution
   * (slice R1): a customer may review a delivered item even after its listing was unpublished. The
   * public plane never uses this; its reads stay PUBLISHED-only.
   */
  Optional<ProductListing> findBySlug(UUID orgId, String slug);

  /**
   * Public slugs for a set of products (org-scoped, any status), keyed by {@code productId} —
   * batch-loaded so the portal order detail can point each line at its listing (the "rate this
   * item" entry, slice R1) without a per-row fetch. Products with no listing are absent from the
   * map. Product ⇄ listing is 1:1 per org (unique index), so at most one row per id.
   */
  Map<UUID, String> findSlugsByProductIds(UUID orgId, Collection<UUID> productIds);

  List<ProductListing> findAll(UUID orgId, int offset, int limit);

  List<ProductListing> findAllByStatus(UUID orgId, ListingStatus status, int offset, int limit);

  /**
   * The one storefront filtered read ({@code stories/storefront_search_and_filters.md}, B3),
   * generalizing — and subsuming — the old {@code findByCategoryAndStatus}: every filter beyond
   * {@code orgId} + {@code status} is an <b>optional predicate</b>, ANDed only when present.
   *
   * <ul>
   *   <li>{@code categoryId} — narrow to one category (via the listing⇄category join); null = all.
   *   <li>{@code q} — case-insensitive substring over {@code title} OR {@code marketing_copy},
   *       bound as a parameter (never interpolated); null = no text filter. The caller passes a
   *       trimmed, non-blank term or null — blank handling is the service's job.
   *   <li>{@code minPrice} / {@code maxPrice} — inclusive bounds on {@code sales_price}; null =
   *       unbounded.
   * </ul>
   *
   * <p>{@code status} is supplied by the caller but the storefront always passes PUBLISHED — the
   * status is never a public parameter. {@code sort} orders per {@link ListingSort}, always
   * tie-broken by {@code slug ASC} so paging is deterministic. {@code featuredOnly} (slice C3) adds
   * the optional {@code featured_sort IS NOT NULL} predicate — ANDed with everything else — so
   * {@code ?featured=true&category=x} intersects the two.
   */
  List<ProductListing> findByFilters(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      boolean featuredOnly,
      ListingSort sort,
      int offset,
      int limit);

  long count(UUID orgId);

  long countByStatus(UUID orgId, ListingStatus status);

  /**
   * Count over exactly the same predicate set as {@link #findByFilters} so a page's {@code total}
   * always matches its rows.
   */
  long countByFilters(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      boolean featuredOnly);

  // --- featured curation (slice C3) ---

  /**
   * The org's featured listings in curated order ({@code featured_sort ASC}) — every status (a
   * DRAFT may be staged), for the admin picker. The public read never calls this; it filters
   * through {@link #findByFilters} with {@code featuredOnly} + PUBLISHED.
   */
  List<ProductListing> findFeatured(UUID orgId);

  /**
   * Count how many of {@code ids} actually belong to {@code orgId} — the set-replace ownership
   * guard (every id must be the org's, else the write is a 400 with nothing applied).
   */
  long countInOrg(UUID orgId, Collection<UUID> ids);

  /**
   * Atomically set-replace the org's featured list: every id in {@code orderedIds} gets {@code
   * featured_sort = its index} (0..n-1); every other listing in the org is cleared to {@code NULL}.
   * Idempotent — replaying the same list yields the same state. Ownership/duplicate/cap validation
   * is the service's job; this method assumes a clean, org-owned, deduplicated list.
   */
  void setFeatured(UUID orgId, List<UUID> orderedIds);

  ProductListing insert(ProductListing listing);

  /** Update the editable content fields (title, marketing copy, slug, sales price). */
  ProductListing update(ProductListing listing);

  /** Update lifecycle only (status + published_at + updated_at). */
  ProductListing updateStatus(ProductListing listing);

  void deleteById(UUID orgId, UUID id);

  boolean existsByProductId(UUID orgId, UUID productId);

  boolean existsBySlug(UUID orgId, String slug);

  boolean existsBySlugAndIdNot(UUID orgId, String slug, UUID excludeId);

  /** True if a product with {@code productId} exists in {@code orgId}. */
  boolean productExists(UUID orgId, UUID productId);

  // --- listing ⇄ category (many-to-many) ---

  /** Replace the listing's category set wholesale. */
  void replaceCategories(UUID listingId, Set<UUID> categoryIds);

  List<UUID> findCategoryIds(UUID listingId);

  /**
   * Category ids for many listings in one query, grouped {@code listingId → [categoryId]}, so a
   * listing page can carry each row's categories without one {@link #findCategoryIds} per row
   * (N+1). Listings with no categories are simply absent from the map.
   */
  Map<UUID, List<UUID>> findCategoryIdsForListings(Collection<UUID> listingIds);

  /** Count how many of {@code categoryIds} actually exist in {@code orgId} (validation). */
  long countCategoriesInOrg(UUID orgId, Set<UUID> categoryIds);

  // --- images ---

  ProductListingImage insertImage(ProductListingImage image);

  List<ProductListingImage> findImages(UUID listingId);

  /**
   * Images for many listings in one query (storefront grid), ordered by listing then sort order, so
   * the caller can group in memory instead of issuing one {@link #findImages} per listing (N+1).
   */
  List<ProductListingImage> findImagesForListings(Collection<UUID> listingIds);

  /**
   * Primary-image object keys for a set of products (org-scoped), keyed by {@code productId}. The
   * primary image is the lowest {@code sort_order} (then oldest) image of the product's listing.
   * Products with no listing, or a listing with no image, are simply absent from the map. Batch-
   * loaded in one query so the stock-overview thumbnail never issues a per-row fetch (N+1).
   */
  Map<UUID, String> findPrimaryImageObjectKeys(UUID orgId, Collection<UUID> productIds);

  /**
   * Update an existing image's editable fields ({@code alt_text}, {@code sort_order}), scoped to
   * {@code orgId} + {@code listingId}. Enables alt-text edits and reordering without a
   * delete-and-re-add. Throws if no such image exists.
   */
  ProductListingImage updateImage(
      UUID orgId, UUID listingId, UUID imageId, String altText, int sortOrder);

  void deleteImage(UUID orgId, UUID listingId, UUID imageId);
}
