package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Public, anonymous storefront <b>read model</b> (CQRS query side) over the Catalog context. Not an
 * aggregate — it owns no state. It resolves an org by its public slug, serves <b>only PUBLISHED</b>
 * listings (hard-coded, never a caller-supplied status), and exposes a deliberately whitelisted
 * shape: no internal ids, no {@code product_id}, no status/timestamps, no object keys — images are
 * short-lived presigned GET URLs. This is what makes a future bug physically unable to leak a draft
 * or an internal field to a shopper: the read path only ever touches {@code product_listing}.
 */
public class StorefrontService {

  private final DSLContext rootDsl;
  private final OrgRepositoryFactory orgRepoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final CategoryRepositoryFactory categoryRepoFactory;
  private final ObjectStorage storage;

  public StorefrontService(
      DSLContext rootDsl,
      OrgRepositoryFactory orgRepoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      CategoryRepositoryFactory categoryRepoFactory,
      ObjectStorage storage) {
    this.rootDsl = rootDsl;
    this.orgRepoFactory = orgRepoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.categoryRepoFactory = categoryRepoFactory;
    this.storage = storage;
  }

  /** A public image — a display URL only; the object key is never exposed. */
  public record PublicImage(String url, String altText, int sortOrder) {}

  /** A category reference on a listing (breadcrumb / chip). */
  public record CategoryRef(String name, String slug) {}

  /** The public view of a listing. Carries no internal id, product id, status or timestamps. */
  public record ListingView(
      String slug,
      String title,
      String marketingCopy,
      java.math.BigDecimal salesPrice,
      List<PublicImage> images,
      List<CategoryRef> categories) {}

  /** A node in the public category nav; {@code parentSlug} is null at the root. */
  public record CategoryNav(String name, String slug, String parentSlug) {}

  /** A page of public listings. */
  public record ListingPage(List<ListingView> items, long total, int page, int size) {}

  // ───────── reads ─────────

  public ListingPage listPublished(String orgSlug, String categorySlug, int page, int size) {
    int offset = Pagination.offset(page, size);

    UUID orgId = resolveOrg(orgSlug).getId();
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);

    List<ProductListing> rows;
    long total;
    if (categorySlug != null && !categorySlug.isBlank()) {
      Category category =
          categoryRepoFactory
              .create(rootDsl)
              .findBySlug(orgId, categorySlug)
              .orElseThrow(() -> new NotFoundException("Category not found: " + categorySlug));
      rows =
          listings.findByCategoryAndStatus(
              orgId, category.getId(), ListingStatus.PUBLISHED, offset, size);
      total = listings.countByCategoryAndStatus(orgId, category.getId(), ListingStatus.PUBLISHED);
    } else {
      rows = listings.findAllByStatus(orgId, ListingStatus.PUBLISHED, offset, size);
      total = listings.countByStatus(orgId, ListingStatus.PUBLISHED);
    }

    // One batched image query for the whole page (avoids an N+1), and the grid presigns only each
    // listing's primary image — full galleries and category breadcrumbs are a detail-view concern.
    Map<UUID, ProductListingImage> primaryByListing = new HashMap<>();
    for (ProductListingImage img :
        listings.findImagesForListings(rows.stream().map(ProductListing::getId).toList())) {
      // Ordered by sort_order asc, so the first one seen per listing is its primary image.
      primaryByListing.putIfAbsent(img.getListingId(), img);
    }
    List<ListingView> items =
        rows.stream()
            .map(
                l -> {
                  ProductListingImage primary = primaryByListing.get(l.getId());
                  List<PublicImage> images =
                      primary == null ? List.of() : List.of(toPublicImage(primary));
                  return new ListingView(
                      l.getSlug(),
                      l.getTitle(),
                      l.getMarketingCopy(),
                      l.getSalesPrice(),
                      images,
                      List.of());
                })
            .toList();
    return new ListingPage(items, total, page, size);
  }

  public ListingView getListing(String orgSlug, String listingSlug) {
    UUID orgId = resolveOrg(orgSlug).getId();
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);
    ProductListing listing =
        listings
            .findBySlugAndStatus(orgId, listingSlug, ListingStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("Listing not found: " + listingSlug));

    List<UUID> categoryIds = listings.findCategoryIds(listing.getId());
    List<CategoryRef> categories =
        categoryRepoFactory.create(rootDsl).findByIds(orgId, categoryIds).stream()
            .map(c -> new CategoryRef(c.getName(), c.getSlug()))
            .toList();
    return toView(listings, listing, categories);
  }

  public List<CategoryNav> listCategories(String orgSlug) {
    UUID orgId = resolveOrg(orgSlug).getId();
    CategoryRepository repo = categoryRepoFactory.create(rootDsl);
    List<Category> all = repo.findAllByOrg(orgId);
    Map<UUID, String> slugById = new HashMap<>();
    for (Category c : all) {
      slugById.put(c.getId(), c.getSlug());
    }
    return all.stream()
        .map(
            c ->
                new CategoryNav(
                    c.getName(),
                    c.getSlug(),
                    c.getParentCategoryId() == null ? null : slugById.get(c.getParentCategoryId())))
        .toList();
  }

  // ───────── helpers ─────────

  private Org resolveOrg(String orgSlug) {
    OrgRepository orgRepo = orgRepoFactory.create(rootDsl);
    return orgRepo
        .findBySlug(orgSlug)
        .filter(Org::isActive)
        .orElseThrow(() -> new NotFoundException("Storefront not found: " + orgSlug));
  }

  private ListingView toView(
      ProductListingRepository listings, ProductListing l, List<CategoryRef> categories) {
    List<PublicImage> images =
        listings.findImages(l.getId()).stream().map(this::toPublicImage).toList();
    return new ListingView(
        l.getSlug(), l.getTitle(), l.getMarketingCopy(), l.getSalesPrice(), images, categories);
  }

  private PublicImage toPublicImage(ProductListingImage img) {
    return new PublicImage(
        storage.presignGet(img.getObjectKey()), img.getAltText(), img.getSortOrder());
  }
}
