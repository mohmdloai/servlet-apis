package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import java.util.List;
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

  /** Count how many of {@code categoryIds} actually exist in {@code orgId} (validation). */
  long countCategoriesInOrg(UUID orgId, Set<UUID> categoryIds);

  // --- images ---

  ProductListingImage insertImage(ProductListingImage image);

  List<ProductListingImage> findImages(UUID listingId);

  void deleteImage(UUID orgId, UUID listingId, UUID imageId);
}
