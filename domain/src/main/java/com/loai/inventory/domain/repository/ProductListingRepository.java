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

  // --- listing CRUD ---

  Optional<ProductListing> findById(UUID orgId, UUID id);

  /** Storefront read: a listing by its public slug, constrained to a status (e.g. PUBLISHED). */
  Optional<ProductListing> findBySlugAndStatus(UUID orgId, String slug, ListingStatus status);

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
   * tie-broken by {@code slug ASC} so paging is deterministic.
   */
  List<ProductListing> findByFilters(
      UUID orgId,
      ListingStatus status,
      UUID categoryId,
      String q,
      BigDecimal minPrice,
      BigDecimal maxPrice,
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
      BigDecimal maxPrice);

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
   * Update an existing image's editable fields ({@code alt_text}, {@code sort_order}), scoped to
   * {@code orgId} + {@code listingId}. Enables alt-text edits and reordering without a
   * delete-and-re-add. Throws if no such image exists.
   */
  ProductListingImage updateImage(
      UUID orgId, UUID listingId, UUID imageId, String altText, int sortOrder);

  void deleteImage(UUID orgId, UUID listingId, UUID imageId);
}
