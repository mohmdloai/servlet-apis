package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

  private final DSLContext rootDsl;
  private final ProductListingRepositoryFactory repoFactory;
  private final ObjectStorage storage;

  public ProductListingService(
      DSLContext rootDsl, ProductListingRepositoryFactory repoFactory, ObjectStorage storage) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.storage = storage;
  }

  /** A presigned image with a display-ready URL (object keys are never exposed to clients). */
  public record ImageView(UUID id, String url, String altText, int sortOrder) {}

  /** A listing plus its category ids and presigned images — the detail view. */
  public record ListingView(
      ProductListing listing, List<UUID> categoryIds, List<ImageView> images) {}

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
    return new ListingView(listing, categoryIds, images);
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

    List<UUID> ids = listings.stream().map(ProductListing::getId).toList();
    Map<UUID, List<UUID>> categoriesByListing = repo.findCategoryIdsForListings(ids);
    Map<UUID, List<ImageView>> imagesByListing =
        repo.findImagesForListings(ids).stream()
            .collect(
                Collectors.groupingBy(
                    ProductListingImage::getListingId,
                    Collectors.mapping(this::toImageView, Collectors.toList())));

    return listings.stream()
        .map(
            l ->
                new ListingView(
                    l,
                    categoriesByListing.getOrDefault(l.getId(), List.of()),
                    imagesByListing.getOrDefault(l.getId(), List.of())))
        .toList();
  }

  public long count(UUID orgId, ListingStatus status) {
    ProductListingRepository repo = repoFactory.create(rootDsl);
    return status == null ? repo.count(orgId) : repo.countByStatus(orgId, status);
  }

  // --- listing CRUD ---

  public ProductListing create(
      UUID orgId,
      UUID productId,
      String title,
      String marketingCopy,
      String slug,
      BigDecimal salesPrice) {
    if (productId == null) throw new ValidationException("product_id is required");
    validateContent(title, slug, salesPrice);

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
          if (repo.existsBySlug(orgId, slug)) {
            throw new ConflictException("Listing slug already used in this org: " + slug);
          }

          ProductListing listing = new ProductListing();
          listing.setOrgId(orgId);
          listing.setProductId(productId);
          listing.setTitle(title);
          listing.setMarketingCopy(marketingCopy);
          listing.setSlug(slug);
          listing.setSalesPrice(salesPrice);
          listing.setStatus(ListingStatus.DRAFT);
          listing.setPublishedAt(null);

          ProductListing saved = repo.insert(listing);
          log.info(
              "Created product_listing id={} orgId={} productId={}",
              saved.getId(),
              orgId,
              productId);
          return saved;
        });
  }

  public ProductListing update(
      UUID orgId, UUID id, String title, String marketingCopy, String slug, BigDecimal salesPrice) {
    validateContent(title, slug, salesPrice);

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

          existing.setTitle(title);
          existing.setMarketingCopy(marketingCopy);
          existing.setSlug(slug);
          existing.setSalesPrice(salesPrice);

          ProductListing updated = repo.update(existing);
          log.info("Updated product_listing id={} orgId={}", id, orgId);
          return updated;
        });
  }

  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("ProductListing", id));
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

  private void validateContent(String title, String slug, BigDecimal salesPrice) {
    if (title == null || title.isBlank()) {
      throw new ValidationException("title is required");
    }
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
}
