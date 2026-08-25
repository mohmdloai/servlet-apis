package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Collection;
import com.loai.inventory.domain.model.CollectionTranslation;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.CollectionRepository;
import com.loai.inventory.domain.repository.CollectionRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merchant-defined named collections — roadmap item 8, {@code stories/storefront_collections.md}.
 * Curation only: this service owns the collection's identity (bilingual name, public slug, rail
 * order) and its ordered membership. It never touches a listing — deleting a collection drops its
 * membership rows and nothing else — and it never decides what serves publicly: any status is
 * storable so a DRAFT can be staged for launch, and the public reads hard-code PUBLISHED.
 *
 * <p>Additive beside {@code product_listing.featured_sort}, which stays exactly as shipped:
 * featured is the zero-config single home strip, collections are the N-named-lists feature next to
 * it.
 */
public class CollectionService {
  private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

  /**
   * Collections per org — a merchant with 30 shelves has a navigation problem, not a cap problem.
   */
  static final int MAX_COLLECTIONS = 30;

  /**
   * Listings per collection. Larger than featured's 12 because a collection is a paginated landing
   * page rather than a home strip, but still bounded — the set-replace ships the whole list in one
   * body.
   */
  static final int MAX_LISTINGS = 100;

  /** Defensive cap on a stored collection name (the {@code CategoryService} bound). */
  static final int MAX_NAME_CHARS = 255;

  /** Rail positions are small non-negative integers; anything else is a caller error. */
  static final int MAX_SORT_ORDER = 9_999;

  /**
   * The slug is a public URL segment ({@code /col/{slug}}), so it takes the org-slug discipline
   * rather than the looser listing-slug rule: lowercase alphanumerics and hyphens, no leading or
   * trailing hyphen, 2–80 chars (the column is VARCHAR(80)).
   */
  private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,78}[a-z0-9]$");

  /**
   * The BCP-47 languages the storefront serves today; widening is a CHECK edit (V71) + this set.
   */
  private static final Set<String> SUPPORTED_LOCALES = Set.of("ar", "en");

  private final DSLContext rootDsl;
  private final CollectionRepositoryFactory repoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final ProductListingService listingService;
  private final ObjectStorage storage;

  public CollectionService(
      DSLContext rootDsl,
      CollectionRepositoryFactory repoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      ProductListingService listingService,
      ObjectStorage storage) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.listingService = listingService;
    this.storage = storage;
  }

  /**
   * A collection plus every authored language and its membership size — the admin list row. {@code
   * collection.name} is the org's default-locale name (the label); {@code translations} carries the
   * paired ar/en inputs the editor renders.
   */
  public record CollectionView(
      Collection collection,
      List<CollectionTranslation> translations,
      long listingCount,
      String imageUrl) {}

  // --- admin reads ---

  /** The org's collections in rail order, each with both names + its listing count (batch). */
  public List<CollectionView> getAll(UUID orgId) {
    CollectionRepository repo = repoFactory.create(rootDsl);
    List<Collection> collections = repo.findAll(orgId);
    if (collections.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = collections.stream().map(Collection::getId).toList();
    Map<UUID, List<CollectionTranslation>> translations = repo.findTranslationsForCollections(ids);
    Map<UUID, Long> counts = repo.listingCounts(ids);
    return collections.stream()
        .map(
            c ->
                new CollectionView(
                    c,
                    translations.getOrDefault(c.getId(), List.of()),
                    counts.getOrDefault(c.getId(), 0L),
                    imageUrlOf(c)))
        .toList();
  }

  public CollectionView getById(UUID orgId, UUID id) {
    CollectionRepository repo = repoFactory.create(rootDsl);
    Collection collection =
        repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Collection", id));
    List<UUID> listingIds = repo.findListingIds(id);
    return new CollectionView(
        collection, repo.findTranslations(id), listingIds.size(), imageUrlOf(collection));
  }

  /**
   * The collection's curated listings, enriched exactly like the featured picker's rows (thumbnail,
   * status badge, categories) and in the curated order — every status, since only PUBLISHED serves
   * publicly.
   */
  public List<ProductListingService.ListingView> getListings(UUID orgId, UUID id) {
    CollectionRepository repo = repoFactory.create(rootDsl);
    repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Collection", id));
    return listingService.viewsByIds(orgId, repo.findListingIds(id));
  }

  // --- admin writes ---

  /**
   * Create a collection. The slug is org-unique (409 on a duplicate) and the org is capped at
   * {@link #MAX_COLLECTIONS} (400). A name in the org's default locale is required; the other
   * language is optional.
   */
  public Collection create(
      UUID orgId, String slug, String nameAr, String nameEn, Integer sortOrder) {
    return create(orgId, slug, nameAr, nameEn, sortOrder, null);
  }

  /**
   * Create with an optional collection image ({@code stories/collection_image.md}): the key must
   * carry this org's {@code {orgId}/collection/} prefix (minted by {@link #presignImageUpload});
   * blank/absent = no image.
   */
  public Collection create(
      UUID orgId,
      String slug,
      String nameAr,
      String nameEn,
      Integer sortOrder,
      String imageObjectKey) {
    String cleanSlug = normalizeSlug(slug);
    int order = normalizeSortOrder(sortOrder);
    String imageKey = cleanImageKey(orgId, imageObjectKey);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CollectionRepository repo = repoFactory.create(txDsl);

          if (repo.count(orgId) >= MAX_COLLECTIONS) {
            throw new ValidationException("at most " + MAX_COLLECTIONS + " collections per org");
          }
          if (repo.existsBySlug(orgId, cleanSlug)) {
            throw new ConflictException("Collection slug already used in this org: " + cleanSlug);
          }

          List<CollectionTranslation> translations =
              normalizeTranslations(orgId, nameAr, nameEn, txDsl);

          Collection collection = new Collection();
          collection.setOrgId(orgId);
          collection.setSlug(cleanSlug);
          collection.setSortOrder(order);
          collection.setImageObjectKey(imageKey);

          Collection saved = repo.insert(collection);
          repo.replaceTranslations(saved.getId(), translations);
          // The INSERT RETURNING carries no name (there is no name column) — surface the
          // default-locale row on the returned object for the response scalar.
          saved.setName(translations.get(0).name());
          log.info(
              "Created collection id={} orgId={} slug={} langs={}",
              saved.getId(),
              orgId,
              cleanSlug,
              translations.size());
          return saved;
        });
  }

  /**
   * Edit a collection's names, slug and rail order. The slug is editable — unlike an invoice number
   * a collection slug carries no money history, so a merchant renaming "ramadan-2026" is a rename,
   * not a rewrite of the past. The old slug simply stops resolving (an empty landing page, per the
   * unknown-slug convention).
   */
  public Collection update(
      UUID orgId, UUID id, String slug, String nameAr, String nameEn, Integer sortOrder) {
    return update(orgId, id, slug, nameAr, nameEn, sortOrder, null);
  }

  /**
   * Update with the image merged the banner way: {@code imageObjectKey} absent (null) leaves the
   * stored image untouched (a client that doesn't know about images can't wipe one); blank clears
   * it; a value replaces it after the org-prefix check.
   */
  public Collection update(
      UUID orgId,
      UUID id,
      String slug,
      String nameAr,
      String nameEn,
      Integer sortOrder,
      String imageObjectKey) {
    String cleanSlug = normalizeSlug(slug);
    int order = normalizeSortOrder(sortOrder);
    String imageKey = imageObjectKey == null ? null : cleanImageKey(orgId, imageObjectKey);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CollectionRepository repo = repoFactory.create(txDsl);

          Collection existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Collection", id));
          if (repo.existsBySlugAndIdNot(orgId, cleanSlug, id)) {
            throw new ConflictException("Collection slug already used in this org: " + cleanSlug);
          }

          List<CollectionTranslation> translations =
              normalizeTranslations(orgId, nameAr, nameEn, txDsl);

          existing.setSlug(cleanSlug);
          existing.setSortOrder(order);
          if (imageObjectKey != null) {
            existing.setImageObjectKey(imageKey);
          }
          Collection updated = repo.update(existing);
          repo.replaceTranslations(id, translations); // PUT replaces the whole set
          updated.setName(translations.get(0).name());
          log.info("Updated collection id={} orgId={} langs={}", id, orgId, translations.size());
          return updated;
        });
  }

  /**
   * Hand out a presigned PUT URL + an org-scoped {@code {orgId}/collection/…} key. No row is
   * written — the client uploads the bytes, then attaches the key via create/update (which
   * re-checks the prefix). The banner / category presign machinery, one more prefix.
   */
  public ImagePresign presignImageUpload(UUID orgId, String filename, String contentType) {
    if (filename == null || filename.isBlank()) {
      throw new ValidationException("filename is required");
    }
    orgRepoFactory
        .create(rootDsl)
        .findById(orgId)
        .orElseThrow(() -> new NotFoundException("Org", orgId));
    String objectKey = storage.newCollectionKey(orgId, filename);
    return new ImagePresign(
        storage.presignPut(objectKey, contentType), objectKey, storage.presignTtlSeconds());
  }

  /** Blank → null; a value must belong to this org's collection prefix. */
  private static String cleanImageKey(UUID orgId, String raw) {
    if (raw == null) {
      return null;
    }
    String key = raw.trim();
    if (key.isEmpty()) {
      return null;
    }
    if (!key.startsWith(ObjectStorage.collectionKeyPrefix(orgId))) {
      throw new ValidationException("image_object_key does not belong to this org");
    }
    return key;
  }

  private String imageUrlOf(Collection c) {
    return c.getImageObjectKey() == null ? null : storage.presignGet(c.getImageObjectKey());
  }

  /** Delete a collection. Its membership rows cascade away; no listing is ever touched. */
  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CollectionRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Collection", id));
          repo.deleteById(orgId, id);
          log.info("Deleted collection id={} orgId={}", id, orgId);
        });
  }

  /**
   * Atomic set-replace of the collection's membership — the {@code PUT /product-listings/featured}
   * semantics, scoped to one collection. The whole set is validated before a row is touched
   * (nothing applied on any failure): {@code > 100} ids → 400; a duplicate id → 400; an id not in
   * the org → 400. Array order becomes {@code sort} 0..n-1; ids absent from the list leave the
   * collection. Returns the resulting curated list (enriched) for a save-then-refresh.
   */
  public List<ProductListingService.ListingView> setListings(
      UUID orgId, UUID id, List<UUID> listingIds) {
    List<UUID> ids = listingIds == null ? List.of() : listingIds;
    if (ids.size() > MAX_LISTINGS) {
      throw new ValidationException("at most " + MAX_LISTINGS + " listings per collection");
    }
    Set<UUID> distinct = new LinkedHashSet<>(ids);
    if (distinct.size() != ids.size()) {
      throw new ValidationException("duplicate listing ids are not allowed");
    }
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CollectionRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Collection", id));
          ProductListingRepository listings = listingRepoFactory.create(txDsl);
          if (!ids.isEmpty() && listings.countInOrg(orgId, ids) != ids.size()) {
            throw new ValidationException("One or more listing ids do not exist in this org");
          }
          repo.setListings(id, ids);
          log.info("Set {} listings on collection id={} orgId={}", ids.size(), id, orgId);
        });
    return getListings(orgId, id);
  }

  // --- normalization ---

  private String normalizeSlug(String slug) {
    if (slug == null || slug.isBlank()) {
      throw new ValidationException("slug is required");
    }
    String t = slug.trim().toLowerCase(Locale.ROOT);
    if (!SLUG_PATTERN.matcher(t).matches()) {
      throw new ValidationException(
          "slug must be 2-80 chars, lowercase alphanumeric or hyphen, no leading/trailing hyphen");
    }
    return t;
  }

  private int normalizeSortOrder(Integer sortOrder) {
    if (sortOrder == null) {
      return 0;
    }
    if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
      throw new ValidationException("sort_order must be between 0 and " + MAX_SORT_ORDER);
    }
    return sortOrder;
  }

  /**
   * Turn the paired {@code name_ar}/{@code name_en} inputs into the persisted translation set. Each
   * value is NFC-normalized ({@link Text}) and length-capped; one that normalizes to blank counts
   * as not-provided, and the org's {@code default_locale} row must survive that with a non-blank
   * name (else 400). The returned list is default-locale-first — callers read the display scalar
   * off {@code get(0)}.
   */
  private List<CollectionTranslation> normalizeTranslations(
      UUID orgId, String nameAr, String nameEn, DSLContext txDsl) {
    String defaultLocale = defaultLocale(orgId, txDsl);
    Map<String, String> raw = new LinkedHashMap<>();
    raw.put("ar", nameAr);
    raw.put("en", nameEn);

    Map<String, CollectionTranslation> byLang = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : raw.entrySet()) {
      if (!SUPPORTED_LOCALES.contains(e.getKey())) {
        continue;
      }
      String name = Text.normalizeText(e.getValue());
      if (name == null) {
        continue; // blank tab — not provided; the default-locale requirement is checked below
      }
      if (name.length() > MAX_NAME_CHARS) {
        throw new ValidationException("name exceeds the " + MAX_NAME_CHARS + "-character limit");
      }
      byLang.put(e.getKey(), new CollectionTranslation(e.getKey(), name));
    }

    CollectionTranslation defaultRow = byLang.get(defaultLocale);
    if (defaultRow == null) {
      throw new ValidationException(
          "a name in the org's default locale (" + defaultLocale + ") is required");
    }
    List<CollectionTranslation> ordered = new ArrayList<>(byLang.size());
    ordered.add(defaultRow);
    byLang.forEach(
        (lang, t) -> {
          if (!lang.equals(defaultLocale)) {
            ordered.add(t);
          }
        });
    return ordered;
  }

  private String defaultLocale(UUID orgId, DSLContext txDsl) {
    OrgRepository orgRepo = orgRepoFactory.create(txDsl);
    Org org = orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
    String loc = org.getDefaultLocale();
    return loc == null || loc.isBlank() ? "ar" : loc.trim().toLowerCase(Locale.ROOT);
  }
}
