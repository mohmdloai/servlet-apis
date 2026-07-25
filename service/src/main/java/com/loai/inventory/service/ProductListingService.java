package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.model.ProductListingTranslation;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.ProductVariantRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The storefront-facing listing aggregate, decoupled from the internal {@code Product}. Owns the
 * {@code DRAFT → PUBLISHED → ARCHIVED} lifecycle, the listing⇄category set, and image attachment
 * via presigned object-storage uploads. One listing per product per org.
 */
public class ProductListingService {
  private static final Logger log = LoggerFactory.getLogger(ProductListingService.class);

  /** Max length for a translated title / marketing-copy value (defensive cap on stored text). */
  static final int MAX_TITLE_CHARS = 255;

  static final int MAX_COPY_CHARS = 20_000;

  private final DSLContext rootDsl;
  private final ProductListingRepositoryFactory repoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final ProductVariantRepositoryFactory variantRepoFactory;
  private final ObjectStorage storage;

  public ProductListingService(
      DSLContext rootDsl,
      ProductListingRepositoryFactory repoFactory,
      OrgRepositoryFactory orgRepoFactory,
      ProductVariantRepositoryFactory variantRepoFactory,
      ObjectStorage storage) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.variantRepoFactory = variantRepoFactory;
    this.storage = storage;
  }

  /** A presigned image with a display-ready URL (object keys are never exposed to clients). */
  public record ImageView(UUID id, String url, String altText, int sortOrder) {}

  /**
   * A listing plus its category ids, presigned images, and every language's translation — the admin
   * detail/list view. {@code translations} carries all authored languages (slice L2); the base
   * {@code listing}'s {@code title}/{@code marketingCopy} stay populated (dual-written to the org's
   * default locale) for the card label and pre-L6 rollback.
   */
  public record ListingView(
      ProductListing listing,
      List<UUID> categoryIds,
      List<ImageView> images,
      List<ProductListingTranslation> translations) {}

  /**
   * The localized content of a create/update write (slice L2). {@code translations} is the authored
   * per-language set; when it is null/empty the legacy single {@code title}/{@code marketingCopy}
   * are synthesized into one row at the org's default locale (backward-compatible with pre-L2
   * callers). The service normalizes, caps, and validates the default-locale row before persisting.
   */
  public record TranslatedContentInput(
      List<ProductListingTranslation> translations, String title, String marketingCopy) {}

  /** The result of requesting an upload slot: where to PUT, and the key to attach afterward. */
  public record PresignResult(String uploadUrl, String objectKey, long expiresInSeconds) {}

  // --- reads ---

  /**
   * Presigned primary-image URLs for a set of products (org-scoped), keyed by {@code productId} —
   * the stock-overview / out-of-stock thumbnails. A product with no listing, or a listing with no
   * image, is simply absent from the map (the caller renders a placeholder). One batch query, then
   * a local presign per hit. Read-only on {@code rootDsl}.
   */
  public Map<UUID, String> primaryImageUrlsByProductId(
      UUID orgId, java.util.Collection<UUID> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    ProductListingRepository repo = repoFactory.create(rootDsl);
    Map<UUID, String> keys = repo.findPrimaryImageObjectKeys(orgId, productIds);
    Map<UUID, String> urls = new java.util.HashMap<>(keys.size());
    keys.forEach((productId, key) -> urls.put(productId, storage.presignGet(key)));
    return urls;
  }

  public ListingView getById(UUID orgId, UUID id) {
    ProductListingRepository repo = repoFactory.create(rootDsl);
    ProductListing listing =
        repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));
    List<UUID> categoryIds = repo.findCategoryIds(id);
    List<ImageView> images = repo.findImages(id).stream().map(this::toImageView).toList();
    return new ListingView(listing, categoryIds, images, repo.findTranslations(id));
  }

  /**
   * A page of listings, each enriched with its category ids and presigned images — the same shape
   * as {@link #getById}, so list rows can render a thumbnail and category count. The images and
   * categories are batch-loaded (two queries per page, not per row) to avoid N+1.
   */
  public List<ListingView> getAll(UUID orgId, ListingStatus status, int page, int size) {
    int offset = Pagination.offset(page, size);
    ProductListingRepository repo = repoFactory.create(rootDsl);
    List<ProductListing> listings =
        status == null
            ? repo.findAll(orgId, offset, size)
            : repo.findAllByStatus(orgId, status, offset, size);
    if (listings.isEmpty()) {
      return List.of();
    }

    return enrich(listings, repo);
  }

  /**
   * Decorate a page of listing rows with their categories, presigned images and translations —
   * three batch queries for the whole page, never per row. The returned list preserves {@code
   * listings}' order, so the caller owns the ordering (list: {@code created_at}; featured / a
   * collection: the curated position).
   */
  private List<ListingView> enrich(List<ProductListing> listings, ProductListingRepository repo) {
    if (listings.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = listings.stream().map(ProductListing::getId).toList();
    Map<UUID, List<UUID>> categoriesByListing = repo.findCategoryIdsForListings(ids);
    Map<UUID, List<ImageView>> imagesByListing =
        repo.findImagesForListings(ids).stream()
            .collect(
                Collectors.groupingBy(
                    ProductListingImage::getListingId,
                    Collectors.mapping(this::toImageView, Collectors.toList())));
    Map<UUID, List<ProductListingTranslation>> translationsByListing =
        repo.findTranslationsForListings(ids);

    return listings.stream()
        .map(
            l ->
                new ListingView(
                    l,
                    categoriesByListing.getOrDefault(l.getId(), List.of()),
                    imagesByListing.getOrDefault(l.getId(), List.of()),
                    translationsByListing.getOrDefault(l.getId(), List.of())))
        .toList();
  }

  /**
   * Enriched views for an explicit, already-ordered id list — the curation reads' shared back end
   * (roadmap item 8's collection membership; the same {@link ListingView} shape the featured picker
   * renders). Every status is included: a DRAFT may be staged for launch, and only PUBLISHED ever
   * serves publicly. Ids not in the org are silently absent rather than an error — the caller
   * validated ownership when it stored the membership, and a row that vanished is not the reader's
   * problem. The returned list follows {@code orderedIds}, not the database's order.
   */
  public List<ListingView> viewsByIds(UUID orgId, List<UUID> orderedIds) {
    if (orderedIds == null || orderedIds.isEmpty()) {
      return List.of();
    }
    ProductListingRepository repo = repoFactory.create(rootDsl);
    Map<UUID, ProductListing> byId = new LinkedHashMap<>();
    for (ProductListing l : repo.findByIds(orgId, orderedIds)) {
      byId.put(l.getId(), l);
    }
    List<ProductListing> ordered = new ArrayList<>(orderedIds.size());
    for (UUID id : orderedIds) {
      ProductListing l = byId.get(id);
      if (l != null) {
        ordered.add(l);
      }
    }
    return enrich(ordered, repo);
  }

  public long count(UUID orgId, ListingStatus status) {
    ProductListingRepository repo = repoFactory.create(rootDsl);
    return status == null ? repo.count(orgId) : repo.countByStatus(orgId, status);
  }

  // --- featured curation (slice C3) ---

  /** Up to 12 featured listings — the cap; a set-replace above it is a 400 (epic §10). */
  static final int MAX_FEATURED = 12;

  /**
   * The org's featured listings in curated order, each enriched with categories + presigned images
   * (the same {@link ListingView} shape as {@link #getAll}, so the admin picker renders a thumbnail
   * and status badge). Every status is included — a DRAFT may be staged for launch. Batch-loaded
   * (two queries) to avoid N+1.
   */
  public List<ListingView> getFeatured(UUID orgId) {
    ProductListingRepository repo = repoFactory.create(rootDsl);
    return enrich(repo.findFeatured(orgId), repo);
  }

  /**
   * Set-replace the org's featured list from an ordered id list ({@code featured_sort} = index).
   * Validates the whole set before touching a row (atomic — nothing applied on any failure): {@code
   * > 12} ids → 400; a duplicate id → 400; any id not belonging to the org → 400. Ids absent from
   * the list are cleared to NULL. Any status is storable — only PUBLISHED ever serves publicly (the
   * public read's job). Returns the resulting curated list (enriched), for a save-then-refresh.
   */
  public List<ListingView> setFeatured(UUID orgId, List<UUID> listingIds) {
    List<UUID> ids = listingIds == null ? List.of() : listingIds;
    if (ids.size() > MAX_FEATURED) {
      throw new ValidationException("at most " + MAX_FEATURED + " featured listings");
    }
    Set<UUID> distinct = new java.util.LinkedHashSet<>(ids);
    if (distinct.size() != ids.size()) {
      throw new ValidationException("duplicate listing ids are not allowed");
    }
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          if (!ids.isEmpty() && repo.countInOrg(orgId, ids) != ids.size()) {
            throw new ValidationException("One or more listing ids do not exist in this org");
          }
          repo.setFeatured(orgId, ids);
          log.info("Set {} featured listings for orgId={}", ids.size(), orgId);
        });
    return getFeatured(orgId);
  }

  // --- listing CRUD ---

  /** Single-language convenience: title/marketingCopy become the org's default-locale row. */
  public ProductListing create(
      UUID orgId,
      UUID productId,
      String title,
      String marketingCopy,
      String slug,
      BigDecimal salesPrice) {
    return create(
        orgId, productId, slug, salesPrice, new TranslatedContentInput(null, title, marketingCopy));
  }

  /** Single-language convenience: title/marketingCopy become the org's default-locale row. */
  public ProductListing update(
      UUID orgId, UUID id, String title, String marketingCopy, String slug, BigDecimal salesPrice) {
    return update(
        orgId, id, slug, salesPrice, new TranslatedContentInput(null, title, marketingCopy));
  }

  public ProductListing create(
      UUID orgId,
      UUID productId,
      String slug,
      BigDecimal salesPrice,
      TranslatedContentInput content) {
    if (productId == null) throw new ValidationException("product_id is required");
    validateSlugAndPrice(slug, salesPrice);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);

          if (!repo.productExists(orgId, productId)) {
            throw new ValidationException("product_id not found in this org");
          }
          if (repo.existsByProductId(orgId, productId)) {
            throw new ConflictException("This product already has a listing");
          }
          // A variant's child product is sold through its parent's listing and can never acquire
          // one
          // of its own (architecture §1 / §5 #11) — otherwise the same physical item would be
          // buyable under two public identities with two independent prices.
          if (variantRepoFactory.create(txDsl).existsByProductId(orgId, productId)) {
            throw new ConflictException(
                "This product is a variant of another listing and cannot have its own listing");
          }
          if (repo.existsBySlug(orgId, slug)) {
            throw new ConflictException("Listing slug already used in this org: " + slug);
          }

          List<ProductListingTranslation> translations =
              normalizeTranslations(orgId, content, txDsl);
          ProductListingTranslation defaultRow = translations.get(0); // resolver puts default first

          ProductListing listing = new ProductListing();
          listing.setOrgId(orgId);
          listing.setProductId(productId);
          listing.setSlug(slug);
          listing.setSalesPrice(salesPrice);
          listing.setStatus(ListingStatus.DRAFT);
          listing.setPublishedAt(null);

          ProductListing saved = repo.insert(listing);
          repo.replaceTranslations(saved.getId(), translations);
          // The insert RETURNING no longer carries a title/marketing_copy column (dropped at L6);
          // surface the default-locale copy on the returned object for the response scalar.
          saved.setTitle(defaultRow.title());
          saved.setMarketingCopy(defaultRow.marketingCopy());
          log.info(
              "Created product_listing id={} orgId={} productId={} langs={}",
              saved.getId(),
              orgId,
              productId,
              translations.size());
          return saved;
        });
  }

  public ProductListing update(
      UUID orgId, UUID id, String slug, BigDecimal salesPrice, TranslatedContentInput content) {
    validateSlugAndPrice(slug, salesPrice);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);

          ProductListing existing =
              repo.findById(orgId, id)
                  .orElseThrow(() -> new NotFoundException("ProductListing", id));
          if (repo.existsBySlugAndIdNot(orgId, slug, id)) {
            throw new ConflictException("Listing slug already used in this org: " + slug);
          }

          List<ProductListingTranslation> translations =
              normalizeTranslations(orgId, content, txDsl);
          ProductListingTranslation defaultRow = translations.get(0);

          existing.setSlug(slug);
          existing.setSalesPrice(salesPrice);

          ProductListing updated = repo.update(existing);
          repo.replaceTranslations(id, translations); // PUT replaces the whole set
          // The update RETURNING no longer carries a title/marketing_copy column (dropped at L6);
          // surface the default-locale copy on the returned object for the response scalar.
          updated.setTitle(defaultRow.title());
          updated.setMarketingCopy(defaultRow.marketingCopy());
          log.info(
              "Updated product_listing id={} orgId={} langs={}", id, orgId, translations.size());
          return updated;
        });
  }

  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));
          // A listing with a LIVE variant set cannot be deleted — the mirror of the product-delete
          // guard (architecture §5 #12). `product_variant.product_listing_id` is ON DELETE CASCADE,
          // so without this the delete silently dissolves the set: the bridge rows vanish and the
          // stocked children they named are stranded as ordinary listing-less products, which the
          // §5 #11 guard would then let acquire listings of their own. Deactivating the set first
          // is the merchant's explicit "these are off sale", the same consent the product guard
          // takes.
          if (variantRepoFactory.create(txDsl).existsActiveVariantForListing(orgId, id)) {
            throw new ConflictException(
                "Listing has active variants and cannot be deleted — deactivate its variants"
                    + " first");
          }
          repo.deleteById(orgId, id);
          log.info("Deleted product_listing id={} orgId={}", id, orgId);
        });
  }

  // --- lifecycle ---

  public ProductListing publish(UUID orgId, UUID id) {
    return transition(
        orgId,
        id,
        listing -> {
          if (listing.getStatus() != ListingStatus.DRAFT) {
            throw new ConflictException(
                "Only a DRAFT listing can be published (was " + listing.getStatus() + ")");
          }
          listing.setStatus(ListingStatus.PUBLISHED);
          listing.setPublishedAt(OffsetDateTime.now());
        });
  }

  public ProductListing unpublish(UUID orgId, UUID id) {
    return transition(
        orgId,
        id,
        listing -> {
          if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new ConflictException(
                "Only a PUBLISHED listing can be unpublished (was " + listing.getStatus() + ")");
          }
          listing.setStatus(ListingStatus.DRAFT);
          listing.setPublishedAt(null);
        });
  }

  public ProductListing archive(UUID orgId, UUID id) {
    return transition(
        orgId,
        id,
        listing -> {
          if (listing.getStatus() == ListingStatus.ARCHIVED) {
            throw new ConflictException("Listing is already ARCHIVED");
          }
          listing.setStatus(ListingStatus.ARCHIVED);
        });
  }

  private ProductListing transition(
      UUID orgId, UUID id, java.util.function.Consumer<ProductListing> mutate) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          ProductListing listing =
              repo.findById(orgId, id)
                  .orElseThrow(() -> new NotFoundException("ProductListing", id));
          mutate.accept(listing);
          ProductListing updated = repo.updateStatus(listing);
          // updateStatus' RETURNING no longer carries a title/marketing_copy column (dropped at
          // L6);
          // carry the default-locale scalar from the joined findById read onto the response object.
          updated.setTitle(listing.getTitle());
          updated.setMarketingCopy(listing.getMarketingCopy());
          log.info("Listing id={} orgId={} → {}", id, orgId, updated.getStatus());
          return updated;
        });
  }

  // --- categories ---

  public List<UUID> setCategories(UUID orgId, UUID id, Set<UUID> categoryIds) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));

          if (!categoryIds.isEmpty()
              && repo.countCategoriesInOrg(orgId, categoryIds) != categoryIds.size()) {
            throw new ValidationException("One or more category ids do not exist in this org");
          }
          repo.replaceCategories(id, categoryIds);
          log.info("Set {} categories on listing id={} orgId={}", categoryIds.size(), id, orgId);
          return repo.findCategoryIds(id);
        });
  }

  // --- images (presigned object storage) ---

  /**
   * Hand out a presigned PUT URL + the object key. No DB row is created until {@link #attachImage}.
   */
  public PresignResult presignImageUpload(
      UUID orgId, UUID id, String filename, String contentType) {
    if (filename == null || filename.isBlank()) {
      throw new ValidationException("filename is required");
    }
    ProductListingRepository repo = repoFactory.create(rootDsl);
    repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));

    String objectKey = storage.newImageKey(orgId, id, filename);
    String url = storage.presignPut(objectKey, contentType);
    return new PresignResult(url, objectKey, storage.presignTtlSeconds());
  }

  public ImageView attachImage(
      UUID orgId, UUID id, String objectKey, String altText, Integer sortOrder) {
    if (objectKey == null || objectKey.isBlank()) {
      throw new ValidationException("object_key is required");
    }
    // The key must be one we minted for this org+listing — blocks attaching someone else's object.
    if (!objectKey.startsWith(ObjectStorage.keyPrefix(orgId, id))) {
      throw new ValidationException("object_key does not belong to this listing");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));

          ProductListingImage image = new ProductListingImage();
          image.setOrgId(orgId);
          image.setListingId(id);
          image.setObjectKey(objectKey);
          image.setAltText(altText);
          image.setSortOrder(sortOrder == null ? 0 : sortOrder);

          ProductListingImage saved = repo.insertImage(image);
          log.info("Attached image id={} to listing id={} orgId={}", saved.getId(), id, orgId);
          return toImageView(saved);
        });
  }

  public List<ImageView> listImages(UUID orgId, UUID id) {
    ProductListingRepository repo = repoFactory.create(rootDsl);
    repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));
    return repo.findImages(id).stream().map(this::toImageView).toList();
  }

  /** Edit an existing image's alt text and sort order (reorder without delete-and-re-add). */
  public ImageView updateImage(
      UUID orgId, UUID id, UUID imageId, String altText, Integer sortOrder) {
    if (sortOrder == null) {
      throw new ValidationException("sort_order is required");
    }
    if (sortOrder < 0) {
      throw new ValidationException("sort_order must be >= 0");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));
          ProductListingImage updated = repo.updateImage(orgId, id, imageId, altText, sortOrder);
          log.info("Updated image id={} on listing id={} orgId={}", imageId, id, orgId);
          return toImageView(updated);
        });
  }

  public void removeImage(UUID orgId, UUID id, UUID imageId) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));
          repo.deleteImage(orgId, id, imageId);
          log.info("Removed image id={} from listing id={} orgId={}", imageId, id, orgId);
        });
  }

  private ImageView toImageView(ProductListingImage image) {
    return new ImageView(
        image.getId(),
        storage.presignGet(image.getObjectKey()),
        image.getAltText(),
        image.getSortOrder());
  }

  private void validateSlugAndPrice(String slug, BigDecimal salesPrice) {
    if (slug == null || slug.isBlank()) {
      throw new ValidationException("slug is required");
    }
    if (salesPrice == null) {
      throw new ValidationException("sales_price is required");
    }
    if (salesPrice.signum() < 0) {
      throw new ValidationException("sales_price must be >= 0");
    }
  }

  /**
   * The BCP-47 languages the storefront serves today; widening is a CHECK edit (V63) + this set.
   */
  private static final Set<String> SUPPORTED_LOCALES = Set.of("ar", "en");

  /**
   * Normalize + validate a write's translations into the persisted set (slice L2). Accepts either
   * an explicit {@code translations} list or the legacy single {@code title}/{@code marketingCopy}
   * (synthesized into one default-locale row). Every value is NFC-normalized ({@link Text}) and
   * length-capped; an entry whose title normalizes to blank is treated as not-provided (dropped);
   * the org's {@code default_locale} row must survive that with a non-blank title, else 400. The
   * returned list is default-locale-first (callers dual-write the legacy columns from {@code
   * get(0)}).
   */
  private List<ProductListingTranslation> normalizeTranslations(
      UUID orgId, TranslatedContentInput content, DSLContext txDsl) {
    String defaultLocale = defaultLocale(orgId, txDsl);
    List<ProductListingTranslation> source;
    if (content != null && content.translations() != null && !content.translations().isEmpty()) {
      source = content.translations();
    } else {
      source =
          List.of(
              new ProductListingTranslation(
                  defaultLocale,
                  content == null ? null : content.title(),
                  content == null ? null : content.marketingCopy()));
    }

    Map<String, ProductListingTranslation> byLang = new LinkedHashMap<>();
    for (ProductListingTranslation t : source) {
      if (t == null) {
        continue;
      }
      String lang = t.language() == null ? null : t.language().trim().toLowerCase(Locale.ROOT);
      if (lang == null || lang.isBlank()) {
        throw new ValidationException("translation language is required");
      }
      if (!SUPPORTED_LOCALES.contains(lang)) {
        throw new ValidationException("unsupported language: " + lang);
      }
      String title = Text.normalizeText(t.title());
      String copy = Text.normalizeText(t.marketingCopy());
      if (title == null) {
        continue; // blank tab — not provided; the default-locale requirement is checked below
      }
      if (title.length() > MAX_TITLE_CHARS) {
        throw new ValidationException("title exceeds the " + MAX_TITLE_CHARS + "-character limit");
      }
      if (copy != null && copy.length() > MAX_COPY_CHARS) {
        throw new ValidationException(
            "marketing_copy exceeds the " + MAX_COPY_CHARS + "-character limit");
      }
      if (byLang.containsKey(lang)) {
        throw new ValidationException("duplicate translation for language: " + lang);
      }
      byLang.put(lang, new ProductListingTranslation(lang, title, copy));
    }

    ProductListingTranslation defaultRow = byLang.get(defaultLocale);
    if (defaultRow == null) {
      throw new ValidationException(
          "a title in the org's default locale (" + defaultLocale + ") is required");
    }
    List<ProductListingTranslation> ordered = new ArrayList<>(byLang.size());
    ordered.add(defaultRow);
    for (Map.Entry<String, ProductListingTranslation> e : byLang.entrySet()) {
      if (!e.getKey().equals(defaultLocale)) {
        ordered.add(e.getValue());
      }
    }
    return ordered;
  }

  private String defaultLocale(UUID orgId, DSLContext txDsl) {
    OrgRepository orgRepo = orgRepoFactory.create(txDsl);
    Org org = orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
    String loc = org.getDefaultLocale();
    return loc == null || loc.isBlank() ? "ar" : loc.trim().toLowerCase(Locale.ROOT);
  }
}
