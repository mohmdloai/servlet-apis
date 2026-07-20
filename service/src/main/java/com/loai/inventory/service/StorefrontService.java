package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.CategoryTranslation;
import com.loai.inventory.domain.model.ListingSort;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.model.ProductListingTranslation;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.ListingReviewRepository;
import com.loai.inventory.domain.repository.ListingReviewRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepository.CheckoutLineResolution;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontBannerRepositoryFactory;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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

  /**
   * Anonymous system actor for storefront placements — attributes inventory_log rows to storefront.
   */
  private static final ActorContext STOREFRONT_ACTOR = ActorContext.system("storefront");

  private final DSLContext rootDsl;
  private final OrgRepositoryFactory orgRepoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final CategoryRepositoryFactory categoryRepoFactory;
  private final InventoryRepositoryFactory inventoryRepoFactory;
  private final StorefrontBannerRepositoryFactory bannerRepoFactory;
  private final ListingReviewRepositoryFactory reviewRepoFactory;
  private final ObjectStorage storage;
  private final SalesOrderService salesOrderService;
  private final OgImageSource ogImageSource;

  public StorefrontService(
      DSLContext rootDsl,
      OrgRepositoryFactory orgRepoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      CategoryRepositoryFactory categoryRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      StorefrontBannerRepositoryFactory bannerRepoFactory,
      ListingReviewRepositoryFactory reviewRepoFactory,
      ObjectStorage storage,
      SalesOrderService salesOrderService,
      OgImageSource ogImageSource) {
    this.rootDsl = rootDsl;
    this.orgRepoFactory = orgRepoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.categoryRepoFactory = categoryRepoFactory;
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.bannerRepoFactory = bannerRepoFactory;
    this.reviewRepoFactory = reviewRepoFactory;
    this.storage = storage;
    this.salesOrderService = salesOrderService;
    this.ogImageSource = ogImageSource;
  }

  /** A public image — a display URL only; the object key is never exposed. */
  public record PublicImage(String url, String altText, int sortOrder) {}

  /** A category reference on a listing (breadcrumb / chip). */
  public record CategoryRef(String name, String slug) {}

  /**
   * The public view of a listing. Carries no internal id, product id, status or timestamps. {@code
   * inStock} is the advisory availability boolean ({@code available_qty > 0}; untracked → false) —
   * never a quantity (B2). {@code ratingAvg}/{@code ratingCount} are the APPROVED-review aggregate
   * (slice R1, epic §8/§10): computed per page behind the same {@code max-age=60}, one decimal,
   * both {@code null} when the listing has no approved review — absent, never zero-fabricated.
   */
  public record ListingView(
      String slug,
      String title,
      String marketingCopy,
      java.math.BigDecimal salesPrice,
      boolean inStock,
      List<PublicImage> images,
      List<CategoryRef> categories,
      String ratingAvg,
      Long ratingCount) {}

  /** A node in the public category nav; {@code parentSlug} is null at the root. */
  public record CategoryNav(String name, String slug, String parentSlug) {}

  /** A page of public listings. */
  public record ListingPage(List<ListingView> items, long total, int page, int size) {}

  /**
   * The public storefront profile — the whitelisted per-org identity ({@code
   * stories/storefront_org_profile.md}). Carries no internal org field (id, owner, thresholds,
   * order_ttl_minutes, active, timestamps). {@code supportedLocales} is constant {@code [ar, en]}
   * and {@code currency} constant {@code EGP} in v1.
   */
  public record StorefrontProfileView(
      String name,
      String slug,
      String logoUrl,
      String themeColor,
      String defaultLocale,
      List<String> supportedLocales,
      String currency,
      String instapayHandle,
      String paymentInstructions,
      String metaTitle,
      String metaDescription,
      String ogImageVersion) {}

  /** v1 constants: both locales ship live; single-currency platform. */
  private static final List<String> SUPPORTED_LOCALES = List.of("ar", "en");

  private static final String CURRENCY_EGP = "EGP";

  // ───────── reads ─────────

  /**
   * The anonymous storefront profile for {@code orgSlug}: name/slug/logo/theme/locales/currency +
   * payment instructions. 404 on unknown or inactive slug (opaque). The logo object key (shared
   * with the billing profile, V51) is presigned to a short-lived GET URL, or null when unset.
   */
  public StorefrontProfileView profile(String orgSlug) {
    Org org = resolveOrg(orgSlug);
    String logoUrl =
        org.getLogoObjectKey() == null ? null : storage.presignGet(org.getLogoObjectKey());
    String defaultLocale = org.getDefaultLocale() == null ? "ar" : org.getDefaultLocale();
    // meta_title/meta_description cross verbatim (nulls omitted by the DTO's NON_NULL); the profile
    // carries NO og-image URL — the stable GET /api/public/{slug}/og-image route IS the URL, so a
    // presigned URL (which expires) never reaches a meta tag (slice C2, epic §6).
    // og_image_version: a short, one-way hash of the EFFECTIVE og key (og_image_object_key, else
    // logo_object_key — the same fallback chain as ogImage()), null when neither is set. It carries
    // no key material (the key never crosses the public boundary) — only a cache-busting token the
    // storefront appends as ?v= so a changed share image yields a changed og:image URL and social
    // scrapers re-fetch instead of serving the stale cached thumbnail forever.
    String effectiveOgKey = trimToNull(org.getOgImageObjectKey());
    if (effectiveOgKey == null) {
      effectiveOgKey = trimToNull(org.getLogoObjectKey());
    }
    String ogImageVersion = effectiveOgKey == null ? null : shortHash(effectiveOgKey);
    return new StorefrontProfileView(
        org.getName(),
        org.getSlug(),
        logoUrl,
        org.getThemeColor(),
        defaultLocale,
        SUPPORTED_LOCALES,
        CURRENCY_EGP,
        org.getInstapayHandle(),
        org.getPaymentInstructions(),
        org.getMetaTitle(),
        org.getMetaDescription(),
        ogImageVersion);
  }

  /**
   * A short (12 hex chars), stable, one-way hash of an object key — the og-image cache-busting
   * version token. SHA-256 truncated: not reversible to the key (which never crosses the public
   * boundary), yet deterministic — the same image key yields the same {@code ?v=} and a newly
   * attached image yields a different one, which is exactly what forces a scraper re-fetch.
   */
  private static String shortHash(String value) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  // ───────── og-image (C2) ─────────

  /** The bytes + content type of a storefront's og image, for the stable public stream (C2). */
  public record OgImage(byte[] bytes, String contentType) {}

  /**
   * Stream the storefront's social-share (og) image for {@code orgSlug}: resolve the org (active
   * only, else opaque 404) → object key = {@code og_image_object_key}, <b>falling back to {@code
   * logo_object_key}</b> when unset → fetch the bytes via {@link OgImageSource}. Neither key set →
   * {@link NotFoundException}; a storage miss/error also → {@link NotFoundException} (a missing
   * preview beats a hanging crawler, epic §6). The route caller adds {@code Cache-Control: public,
   * max-age=3600} so a third-party cache holds it well past any presign TTL — the stability
   * property (slice C2, {@code stories/storefront_seo_metadata.md}).
   */
  public OgImage ogImage(String orgSlug) {
    Org org = resolveOrg(orgSlug);
    String key = trimToNull(org.getOgImageObjectKey());
    if (key == null) {
      key = trimToNull(org.getLogoObjectKey());
    }
    if (key == null) {
      throw new NotFoundException("No storefront image");
    }
    return ogImageSource
        .fetch(key)
        .map(f -> new OgImage(f.bytes(), f.contentType()))
        .orElseThrow(() -> new NotFoundException("Storefront image unavailable"));
  }

  // ───────── banners (C1) ─────────

  /**
   * A public banner row — whitelisted for the anonymous storefront (customization epic slice C1).
   * Both locale columns cross verbatim so the cache stays one entry per org (the client resolves
   * the path-locale → default-locale fallback, epic §1); {@code imageUrl} is a short-lived
   * presigned GET URL or null (→ gradient slide); the target is the structured slug pair. Carries
   * <b>no</b> id, org id, object key, window bounds, or timestamps.
   */
  public record PublicBannerView(
      String headlineAr,
      String headlineEn,
      String subheadingAr,
      String subheadingEn,
      String imageUrl,
      String targetType,
      String targetSlug) {}

  /**
   * The anonymous home-banner carousel for {@code orgSlug}: the org's banners that are active,
   * in-window, and whose target still resolves (a category that exists, or a <b>PUBLISHED</b>
   * listing), {@code sort_order ASC}. The stale-target filtering is the repository's JOIN (epic §3)
   * — a banner pointing at an unpublished listing silently drops here and returns when it
   * re-publishes, with no edit from the merchant. 404 on unknown/inactive slug (opaque, via {@link
   * #resolveOrg}).
   */
  public List<PublicBannerView> banners(String orgSlug) {
    UUID orgId = resolveOrg(orgSlug).getId();
    return bannerRepoFactory
        .create(rootDsl)
        .findPublicResolved(orgId, OffsetDateTime.now())
        .stream()
        .map(
            b ->
                new PublicBannerView(
                    b.getHeadlineAr(),
                    b.getHeadlineEn(),
                    b.getSubheadingAr(),
                    b.getSubheadingEn(),
                    b.getImageObjectKey() == null
                        ? null
                        : storage.presignGet(b.getImageObjectKey()),
                    b.getTargetType().wire(),
                    b.getTargetSlug()))
        .toList();
  }

  // ───────── checkout (B5) ─────────

  /** One cart line at checkout: a public listing slug + quantity. */
  public record CheckoutLine(String listingSlug, int quantity) {}

  /** The anonymous checkout request: customer form + cart lines + optional notes. */
  public record CheckoutInput(
      SalesOrderService.CustomerInput customer, List<CheckoutLine> lines, String notes) {}

  /** A re-keyed shortage — slug + title, never {@code product_id}. */
  public record StorefrontShortage(
      String listingSlug, String title, int requested, int available) {}

  /**
   * 409 raised from {@link #checkout} when the reservation engine reports a shortage — the internal
   * {@code product_id}-keyed {@link InsufficientStockException} re-mapped to slug + title so no
   * internal id crosses the public boundary.
   */
  public static final class StorefrontOutOfStockException extends ConflictException {
    private final transient List<StorefrontShortage> shortages;

    public StorefrontOutOfStockException(List<StorefrontShortage> shortages) {
      super("One or more items are out of stock");
      this.shortages = List.copyOf(shortages);
    }

    public List<StorefrontShortage> shortages() {
      return shortages;
    }
  }

  /**
   * The customer-safe result of an anonymous checkout: the placed order + lines, the org's payment
   * instructions, the {@code trackUrl}, whether the order was freshly created (201) or an
   * idempotent replay (200), and a {@code productId → listing title} map so the response labels
   * each line with the title the shopper saw (never the internal product description).
   */
  public record CheckoutResult(
      SalesOrder order,
      List<SalesOrderLine> lines,
      String paymentInstructions,
      String trackUrl,
      boolean created,
      Map<UUID, String> titleByProductId) {}

  /**
   * Place an anonymous storefront order ({@code stories/public_checkout.md}, B5). Resolves the org
   * by slug (active-only), resolves every cart slug → internal {@code product_id} + published
   * {@code sales_price} (PUBLISHED-only, server-side), and delegates to {@link
   * SalesOrderService#placeStorefrontOrder}. A slug that is unknown or not PUBLISHED → opaque
   * {@link NotFoundException} (never says which). A reservation shortage → {@link
   * StorefrontOutOfStockException} with slug-keyed shortages.
   */
  public CheckoutResult checkout(String orgSlug, CheckoutInput input, String idempotencyKey) {
    if (input == null || input.lines() == null || input.lines().isEmpty()) {
      throw new com.loai.inventory.common.exception.ValidationException("lines must not be empty");
    }
    Org org = resolveOrg(orgSlug);
    UUID orgId = org.getId();

    // One query resolves all requested (deduplicated) slugs, PUBLISHED-only.
    LinkedHashSet<String> requestedSlugs = new LinkedHashSet<>();
    for (CheckoutLine line : input.lines()) {
      requestedSlugs.add(line.listingSlug());
    }
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);
    Map<String, CheckoutLineResolution> bySlug = new HashMap<>();
    for (CheckoutLineResolution r :
        listings.resolveForCheckout(orgId, requestedSlugs, ListingStatus.PUBLISHED)) {
      bySlug.put(r.slug(), r);
    }

    // Build placement lines in request order; carry the slug/title per product for response labels
    // and 409 re-keying. Any unresolved slug is an opaque 404 (never distinguishes unknown vs
    // draft).
    List<SalesOrderService.StorefrontLineInput> orderLines = new ArrayList<>(input.lines().size());
    Map<UUID, String> titleByProduct = new HashMap<>();
    Map<UUID, String> slugByProduct = new HashMap<>();
    for (CheckoutLine line : input.lines()) {
      CheckoutLineResolution res = bySlug.get(line.listingSlug());
      if (res == null) {
        throw new NotFoundException("Listing not available");
      }
      orderLines.add(
          new SalesOrderService.StorefrontLineInput(
              res.productId(), line.quantity(), res.salesPrice()));
      titleByProduct.put(res.productId(), res.title());
      slugByProduct.put(res.productId(), res.slug());
    }

    try {
      SalesOrderService.StorefrontPlaced placed =
          salesOrderService.placeStorefrontOrder(
              orgId, input.customer(), orderLines, idempotencyKey, input.notes(), STOREFRONT_ACTOR);
      return new CheckoutResult(
          placed.order(),
          placed.lines(),
          org.getPaymentInstructions(),
          placed.trackUrl(),
          placed.created(),
          titleByProduct);
    } catch (InsufficientStockException e) {
      // Re-key each product_id shortage to its listing slug + title — no internal id leaves here.
      List<StorefrontShortage> shortages =
          e.getShortages().stream()
              .map(
                  s ->
                      new StorefrontShortage(
                          slugByProduct.get(s.productId()),
                          titleByProduct.get(s.productId()),
                          s.requested(),
                          s.available()))
              .toList();
      throw new StorefrontOutOfStockException(shortages);
    }
  }

  // ───────── availability (B2) ─────────

  /** One availability row: a public slug + the boolean {@code inStock} (never a quantity). */
  public record AvailabilityView(String slug, boolean inStock) {}

  /**
   * Advisory availability for a cart's slugs ({@code stories/storefront_availability_signal.md}).
   * Resolves the org (active-only), reads availability for the PUBLISHED slugs among the requested
   * ones, and maps every requested slug to {@code inStock} in request order — an unknown or
   * non-PUBLISHED slug is {@code false} (a filter, never a 404, never an oracle). Duplicate slugs
   * are de-duplicated for the read; the response echoes each requested slug once, in first-seen
   * order.
   */
  public List<AvailabilityView> availability(String orgSlug, List<String> slugs) {
    UUID orgId = resolveOrg(orgSlug).getId();
    LinkedHashSet<String> ordered = new LinkedHashSet<>(slugs);
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);
    Map<String, Integer> availableBySlug = new HashMap<>();
    for (ProductListingRepository.ListingAvailability a :
        listings.resolveAvailability(orgId, ordered, ListingStatus.PUBLISHED)) {
      availableBySlug.put(a.slug(), a.available());
    }
    List<AvailabilityView> out = new ArrayList<>(ordered.size());
    for (String slug : ordered) {
      Integer available = availableBySlug.get(slug);
      out.add(new AvailabilityView(slug, available != null && available > 0));
    }
    return out;
  }

  /** The unfiltered/category-only read — delegates with no search, no bounds, default sort. */
  public ListingPage listPublished(String orgSlug, String categorySlug, int page, int size) {
    return listPublished(orgSlug, categorySlug, null, null, null, null, null, null, page, size);
  }

  /** Filtered read without an explicit locale — resolves to the org's default locale. */
  public ListingPage listPublished(
      String orgSlug,
      String categorySlug,
      String q,
      String minPrice,
      String maxPrice,
      String sort,
      String featured,
      int page,
      int size) {
    return listPublished(
        orgSlug, categorySlug, q, minPrice, maxPrice, sort, featured, null, page, size);
  }

  /**
   * The one storefront listings read ({@code stories/storefront_search_and_filters.md}, B3; {@code
   * ?featured=true} added in slice C3): category ∧ substring ∧ price band ∧ featured over the org's
   * PUBLISHED listings, ordered by {@code sort}. PUBLISHED is hard-coded — status is never a
   * parameter. The raw query-param strings are parsed and validated here: unknown {@code sort} →
   * 400 (never a silent default); non-numeric / negative / {@code min > max} price → 400 with a
   * cause-naming message; blank {@code q} → ignored (no filter); {@code featured} other than {@code
   * true}/absent → 400 (same no-silent-coercion convention).
   *
   * <p>When {@code featured=true} and no explicit {@code sort} is given, the default order becomes
   * the merchant's curated {@code featured_sort ASC}; an explicit {@code ?sort=} still overrides
   * it.
   */
  public ListingPage listPublished(
      String orgSlug,
      String categorySlug,
      String q,
      String minPrice,
      String maxPrice,
      String sort,
      String featured,
      String locale,
      int page,
      int size) {
    int offset = Pagination.offset(page, size);

    String query = trimToNull(q);
    boolean featuredOnly = parseFeatured(featured);
    // featured + no explicit sort → curated order; otherwise the usual grammar (blank = NEWEST).
    String sortTrim = trimToNull(sort);
    ListingSort listingSort =
        sortTrim == null
            ? (featuredOnly ? ListingSort.FEATURED : ListingSort.NEWEST)
            : parseSort(sortTrim);
    java.math.BigDecimal min = parsePrice("min_price", minPrice);
    java.math.BigDecimal max = parsePrice("max_price", maxPrice);
    if (min != null && max != null && min.compareTo(max) > 0) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "min_price must not exceed max_price");
    }

    Org org = resolveOrg(orgSlug);
    UUID orgId = org.getId();
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(locale, defaultLocale);
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);

    UUID categoryId = null;
    if (categorySlug != null && !categorySlug.isBlank()) {
      Category category =
          categoryRepoFactory
              .create(rootDsl)
              .findBySlug(orgId, categorySlug)
              .orElseThrow(() -> new NotFoundException("Category not found: " + categorySlug));
      categoryId = category.getId();
    }

    List<ProductListing> rows =
        listings.findByFilters(
            orgId,
            ListingStatus.PUBLISHED,
            categoryId,
            query,
            resolvedLocale,
            defaultLocale,
            min,
            max,
            featuredOnly,
            listingSort,
            offset,
            size);
    long total =
        listings.countByFilters(
            orgId,
            ListingStatus.PUBLISHED,
            categoryId,
            query,
            resolvedLocale,
            defaultLocale,
            min,
            max,
            featuredOnly);

    // One batched image query for the whole page (avoids an N+1), and the grid presigns only each
    // listing's primary image — full galleries and category breadcrumbs are a detail-view concern.
    Map<UUID, ProductListingImage> primaryByListing = new HashMap<>();
    for (ProductListingImage img :
        listings.findImagesForListings(rows.stream().map(ProductListing::getId).toList())) {
      // Ordered by sort_order asc, so the first one seen per listing is its primary image.
      primaryByListing.putIfAbsent(img.getListingId(), img);
    }
    // One more batch query — availability by the page's product_ids (B2), mirroring the image
    // batch.
    // Untracked/absent → not in the map → not in stock.
    Map<UUID, Integer> availableByProduct =
        inventoryRepoFactory
            .create(rootDsl)
            .findAvailableByProductIds(
                orgId, rows.stream().map(ProductListing::getProductId).toList());
    // And the APPROVED-review aggregate for the page (R1, epic §8) — one grouped query, absent
    // when a listing has no approved review (never a fabricated zero).
    Map<UUID, ListingReviewRepository.Aggregate> aggregateByListing =
        reviewRepoFactory
            .create(rootDsl)
            .findAggregates(orgId, rows.stream().map(ProductListing::getId).toList());
    // Batch the per-language content and resolve each listing to the requested locale (L2).
    Map<UUID, List<ProductListingTranslation>> translationsByListing =
        listings.findTranslationsForListings(rows.stream().map(ProductListing::getId).toList());
    List<ListingView> items =
        rows.stream()
            .map(
                l -> {
                  ProductListingImage primary = primaryByListing.get(l.getId());
                  List<PublicImage> images =
                      primary == null ? List.of() : List.of(toPublicImage(primary));
                  boolean inStock = availableByProduct.getOrDefault(l.getProductId(), 0) > 0;
                  ListingReviewRepository.Aggregate agg = aggregateByListing.get(l.getId());
                  ResolvedContent content =
                      resolveContent(
                          translationsByListing.get(l.getId()), resolvedLocale, defaultLocale, l);
                  return new ListingView(
                      l.getSlug(),
                      content.title(),
                      content.marketingCopy(),
                      l.getSalesPrice(),
                      inStock,
                      images,
                      List.of(),
                      formatRatingAvg(agg),
                      agg == null ? null : agg.count());
                })
            .toList();
    return new ListingPage(items, total, page, size);
  }

  /** Detail read without an explicit locale — resolves to the org's default locale. */
  public ListingView getListing(String orgSlug, String listingSlug) {
    return getListing(orgSlug, listingSlug, null);
  }

  public ListingView getListing(String orgSlug, String listingSlug, String locale) {
    Org org = resolveOrg(orgSlug);
    UUID orgId = org.getId();
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(locale, defaultLocale);
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);
    ProductListing listing =
        listings
            .findBySlugAndStatus(orgId, listingSlug, ListingStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("Listing not found: " + listingSlug));

    List<UUID> categoryIds = listings.findCategoryIds(listing.getId());
    CategoryRepository categoryRepo = categoryRepoFactory.create(rootDsl);
    // Resolve each chip's name to the same locale as the listing (L3, per-field fallback).
    Map<UUID, List<CategoryTranslation>> catTranslations =
        categoryRepo.findTranslationsForCategories(categoryIds);
    List<CategoryRef> categories =
        categoryRepo.findByIds(orgId, categoryIds).stream()
            .map(
                c ->
                    new CategoryRef(
                        resolveCategoryName(
                            catTranslations.get(c.getId()), resolvedLocale, defaultLocale, c),
                        c.getSlug()))
            .toList();
    boolean inStock =
        inventoryRepoFactory
                .create(rootDsl)
                .findAvailableByProductIds(orgId, List.of(listing.getProductId()))
                .getOrDefault(listing.getProductId(), 0)
            > 0;
    ResolvedContent content =
        resolveContent(
            listings.findTranslations(listing.getId()), resolvedLocale, defaultLocale, listing);
    return toView(listings, listing, inStock, categories, content);
  }

  /** Nav read without an explicit locale — resolves to the org's default locale. */
  public List<CategoryNav> listCategories(String orgSlug) {
    return listCategories(orgSlug, null);
  }

  public List<CategoryNav> listCategories(String orgSlug, String locale) {
    Org org = resolveOrg(orgSlug);
    UUID orgId = org.getId();
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(locale, defaultLocale);
    CategoryRepository repo = categoryRepoFactory.create(rootDsl);
    List<Category> all = repo.findAllByOrg(orgId);
    Map<UUID, String> slugById = new HashMap<>();
    for (Category c : all) {
      slugById.put(c.getId(), c.getSlug());
    }
    // Batch the per-language names and resolve each node to the requested locale (L3).
    Map<UUID, List<CategoryTranslation>> translations =
        repo.findTranslationsForCategories(all.stream().map(Category::getId).toList());
    return all.stream()
        .map(
            c ->
                new CategoryNav(
                    resolveCategoryName(
                        translations.get(c.getId()), resolvedLocale, defaultLocale, c),
                    c.getSlug(),
                    c.getParentCategoryId() == null ? null : slugById.get(c.getParentCategoryId())))
        .toList();
  }

  // ───────── helpers ─────────

  private static String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }

  /**
   * Parse the {@code sort} param: absent/blank → {@link ListingSort#NEWEST}; anything else must be
   * one of the three wire values — a typo is a 400, never silently coerced to the default.
   */
  private static ListingSort parseSort(String raw) {
    String t = trimToNull(raw);
    if (t == null) {
      return ListingSort.NEWEST;
    }
    return switch (t) {
      case "newest" -> ListingSort.NEWEST;
      case "price_asc" -> ListingSort.PRICE_ASC;
      case "price_desc" -> ListingSort.PRICE_DESC;
      default ->
          throw new com.loai.inventory.common.exception.ValidationException(
              "Parameter 'sort' must be one of: newest, price_asc, price_desc");
    };
  }

  /**
   * Parse the {@code featured} param (slice C3): absent/blank → false; exactly {@code true} → true;
   * anything else → 400 (the same no-silent-coercion rule as {@code sort}). {@code featured=false}
   * is <b>not</b> a valid narrowing — the filter is opt-in, so a stray value is a caller error.
   */
  private static boolean parseFeatured(String raw) {
    String t = trimToNull(raw);
    if (t == null) {
      return false;
    }
    if ("true".equals(t)) {
      return true;
    }
    throw new com.loai.inventory.common.exception.ValidationException(
        "Parameter 'featured' must be 'true' or absent");
  }

  /** Parse a price bound: absent/blank → null; non-numeric or negative → a cause-naming 400. */
  private static java.math.BigDecimal parsePrice(String name, String raw) {
    String t = trimToNull(raw);
    if (t == null) {
      return null;
    }
    java.math.BigDecimal value;
    try {
      value = new java.math.BigDecimal(t);
    } catch (NumberFormatException e) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "Parameter '" + name + "' must be a number");
    }
    if (value.signum() < 0) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "Parameter '" + name + "' must not be negative");
    }
    return value;
  }

  private Org resolveOrg(String orgSlug) {
    OrgRepository orgRepo = orgRepoFactory.create(rootDsl);
    return orgRepo
        .findBySlug(orgSlug)
        .filter(Org::isActive)
        .orElseThrow(() -> new NotFoundException("Storefront not found: " + orgSlug));
  }

  private ListingView toView(
      ProductListingRepository listings,
      ProductListing l,
      boolean inStock,
      List<CategoryRef> categories,
      ResolvedContent content) {
    List<PublicImage> images =
        listings.findImages(l.getId()).stream().map(this::toPublicImage).toList();
    ListingReviewRepository.Aggregate agg =
        reviewRepoFactory
            .create(rootDsl)
            .findAggregates(l.getOrgId(), List.of(l.getId()))
            .get(l.getId());
    return new ListingView(
        l.getSlug(),
        content.title(),
        content.marketingCopy(),
        l.getSalesPrice(),
        inStock,
        images,
        categories,
        formatRatingAvg(agg),
        agg == null ? null : agg.count());
  }

  // ───────── locale resolution (content-localization slice L2) ─────────

  private static String defaultLocaleOf(Org org) {
    String loc = org.getDefaultLocale();
    return loc == null || loc.isBlank() ? "ar" : loc.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * The requested {@code ?locale=} resolved to a served locale: blank/unset → the org's default;
   * anything outside the supported set → 400 (cause-naming, never a silent default — the {@code
   * ?sort=} convention).
   */
  private static String resolveRequestedLocale(String requested, String defaultLocale) {
    if (requested == null || requested.isBlank()) {
      return defaultLocale;
    }
    String loc = requested.trim().toLowerCase(java.util.Locale.ROOT);
    if (!SUPPORTED_LOCALES.contains(loc)) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "unsupported locale: " + loc);
    }
    return loc;
  }

  /** A listing's content resolved to one locale (per-field fallback). */
  private record ResolvedContent(String title, String marketingCopy) {}

  /**
   * Resolve a listing's localized content <b>per field</b>: the requested locale's value, else the
   * default locale's, else the legacy column (never null for {@code title}). {@code marketingCopy}
   * falls back the same way, so a present-but-empty locale copy shows the default's.
   */
  private static ResolvedContent resolveContent(
      List<ProductListingTranslation> translations,
      String preferred,
      String defaultLocale,
      ProductListing fallback) {
    ProductListingTranslation pref = null;
    ProductListingTranslation def = null;
    if (translations != null) {
      for (ProductListingTranslation t : translations) {
        if (t.language().equals(preferred)) {
          pref = t;
        }
        if (t.language().equals(defaultLocale)) {
          def = t;
        }
      }
    }
    String title =
        coalesce(
            pref == null ? null : pref.title(),
            def == null ? null : def.title(),
            fallback.getTitle());
    String copy =
        coalesce(
            pref == null ? null : pref.marketingCopy(),
            def == null ? null : def.marketingCopy(),
            fallback.getMarketingCopy());
    return new ResolvedContent(title, copy);
  }

  private static String coalesce(String a, String b, String c) {
    if (a != null) {
      return a;
    }
    return b != null ? b : c;
  }

  /**
   * Resolve a category's name to one locale (L3): the requested locale's row, else the default
   * locale's, else the legacy {@code category.name} — never null.
   */
  private static String resolveCategoryName(
      List<CategoryTranslation> translations,
      String preferred,
      String defaultLocale,
      Category fallback) {
    String pref = null;
    String def = null;
    if (translations != null) {
      for (CategoryTranslation t : translations) {
        if (t.language().equals(preferred)) {
          pref = t.name();
        }
        if (t.language().equals(defaultLocale)) {
          def = t.name();
        }
      }
    }
    return coalesce(pref, def, fallback.getName());
  }

  /** One-decimal string average (epic §10 — never fabricated precision); null when no aggregate. */
  private static String formatRatingAvg(ListingReviewRepository.Aggregate agg) {
    if (agg == null || agg.count() == 0) {
      return null;
    }
    return agg.average().setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private PublicImage toPublicImage(ProductListingImage img) {
    return new PublicImage(
        storage.presignGet(img.getObjectKey()), img.getAltText(), img.getSortOrder());
  }
}
