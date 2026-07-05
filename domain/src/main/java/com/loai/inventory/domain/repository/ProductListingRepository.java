package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ProductListingRepository {

  // --- listing CRUD ---

  Optional<ProductListing> findById(UUID orgId, UUID id);

  /** Storefront read: a listing by its public slug, constrained to a status (e.g. PUBLISHED). */
  Optional<ProductListing> findBySlugAndStatus(UUID orgId, String slug, ListingStatus status);

  List<ProductListing> findAll(UUID orgId, int offset, int limit);

  List<ProductListing> findAllByStatus(UUID orgId, ListingStatus status, int offset, int limit);

  /** Storefront read: listings in a category with the given status (e.g. PUBLISHED). */
  List<ProductListing> findByCategoryAndStatus(
      UUID orgId, UUID categoryId, ListingStatus status, int offset, int limit);

  long count(UUID orgId);

  long countByStatus(UUID orgId, ListingStatus status);

  long countByCategoryAndStatus(UUID orgId, UUID categoryId, ListingStatus status);

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
