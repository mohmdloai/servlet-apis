package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Collection;
import com.loai.inventory.domain.model.CollectionTranslation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Named collections (roadmap item 8, {@code stories/storefront_collections.md}) — the admin
 * curation plane plus the two public reads (the rail, and the slug resolution behind {@code
 * ?collection={slug}}).
 */
public interface CollectionRepository {

  // --- admin plane ---

  /**
   * The org's collections in rail order ({@code sort_order ASC, slug ASC}), each carrying its
   * default-locale {@code name}.
   */
  List<Collection> findAll(UUID orgId);

  Optional<Collection> findById(UUID orgId, UUID id);

  /** Slug resolution inside one org — the {@code ?collection={slug}} lookup. */
  Optional<Collection> findBySlug(UUID orgId, String slug);

  long count(UUID orgId);

  boolean existsBySlug(UUID orgId, String slug);

  boolean existsBySlugAndIdNot(UUID orgId, String slug, UUID excludeId);

  Collection insert(Collection collection);

  Collection update(Collection collection);

  void deleteById(UUID orgId, UUID id);

  // --- translations (the V63 pattern) ---

  /** Replace the whole per-language name set for a collection (delete-then-insert). */
  void replaceTranslations(UUID collectionId, List<CollectionTranslation> translations);

  /** Every language's name for one collection, ordered by language. */
  List<CollectionTranslation> findTranslations(UUID collectionId);

  /** Batch-load names for a set of collections (avoids N+1), keyed by collection id. */
  Map<UUID, List<CollectionTranslation>> findTranslationsForCollections(
      java.util.Collection<UUID> collectionIds);

  // --- membership ---

  /**
   * The collection's listing ids in curated order ({@code sort ASC}) — every status, since a DRAFT
   * may be staged for launch (only PUBLISHED ever serves publicly).
   */
  List<UUID> findListingIds(UUID collectionId);

  /**
   * Atomically set-replace a collection's membership: every id in {@code orderedIds} gets {@code
   * sort = its index} (0..n-1) and every other membership row is removed. Two statements inside the
   * caller's transaction, so the replace is atomic and idempotent.
   */
  void setListings(UUID collectionId, List<UUID> orderedIds);

  /** Listing counts per collection (batch, no N+1) — the admin list's {@code listing_count}. */
  Map<UUID, Long> listingCounts(java.util.Collection<UUID> collectionIds);

  // --- public plane ---

  /**
   * The rail read: collections that hold at least one PUBLISHED listing, in rail order, each with
   * its name resolved {@code requestedLocale → defaultLocale → slug}. An empty shelf is never
   * advertised — that honesty rule lives in this query rather than in a caller's filter, so every
   * reader gets it.
   */
  List<Collection> findPublicRail(UUID orgId, String locale, String defaultLocale);
}
