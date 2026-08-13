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
import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.model.ProductListingTranslation;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.model.StorefrontBanner;
import com.loai.inventory.domain.model.StorefrontBannerTranslation;
import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import com.loai.inventory.domain.repository.CollectionRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.ListingReviewRepository;
import com.loai.inventory.domain.repository.ListingReviewRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.OrgWhatsAppConfigRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepository.CheckoutLineResolution;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontBannerRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontCrawlRepository;
import com.loai.inventory.domain.repository.StorefrontCrawlRepositoryFactory;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
  private final CollectionRepositoryFactory collectionRepoFactory;
  private final OrgWhatsAppConfigRepositoryFactory whatsAppConfigRepoFactory;
  private final StorefrontCrawlRepositoryFactory crawlRepoFactory;
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
      CollectionRepositoryFactory collectionRepoFactory,
      OrgWhatsAppConfigRepositoryFactory whatsAppConfigRepoFactory,
      StorefrontCrawlRepositoryFactory crawlRepoFactory,
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
    this.collectionRepoFactory = collectionRepoFactory;
    this.whatsAppConfigRepoFactory = whatsAppConfigRepoFactory;
    this.crawlRepoFactory = crawlRepoFactory;
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
      Long ratingCount,
      /**
       * True when this listing sells through variants (VG2). On a <b>row</b> it is the signal that
       * {@code salesPrice} is a "from" price and that the shopper must open the detail to choose;
       * on the <b>detail</b> it says {@link #variants} is the thing to buy, not the listing itself.
       */
      boolean hasVariants,
      /**
       * The active variants, in curated order — <b>detail only</b>; a row carries an empty list (a
       * grid must not pay for it). Each is the public shape: key, label, option labels, its own
       * price and its own stock. No {@code product_id}, SKU or barcode is representable here.
       */
      List<VariantView> variants) {

    /** Row constructor — no variants block, {@code hasVariants} still honest. */
    public ListingView(
        String slug,
        String title,
        String marketingCopy,
        java.math.BigDecimal salesPrice,
        boolean inStock,
        List<PublicImage> images,
        List<CategoryRef> categories,
        String ratingAvg,
        Long ratingCount,
        boolean hasVariants) {
      this(
          slug,
          title,
          marketingCopy,
          salesPrice,
          inStock,
          images,
          categories,
          ratingAvg,
          ratingCount,
          hasVariants,
          List.of());
    }
  }

  /**
   * One selectable variant on the public detail (VG2, architecture §3). {@code key} is the only
   * handle that crosses — it is what a cart line and the availability batch name it by. {@code
   * options} maps each axis slug to the value's localized <b>label</b> ("size" → "M"), so the
   * picker renders without a second lookup, and {@code label} is those joined ("Red / M").
   */
  public record VariantView(
      String key,
      String label,
      Map<String, String> options,
      java.math.BigDecimal price,
      boolean inStock) {}

  /** A node in the public category nav; {@code parentSlug} is null at the root. */
  public record CategoryNav(String name, String slug, String parentSlug) {}

  /**
   * A page of public listings. {@code facets} is <b>null</b> unless the caller asked for it
   * (`?include_facets=true`) — only the catalog page pays for the extra queries, and the envelope
   * stays byte-identical for every strip/home/availability reader that does not.
   */
  public record ListingPage(
      List<ListingView> items, long total, int page, int size, List<FacetGroup> facets) {
    /** Lean constructor — the pre-facets envelope, unchanged. */
    public ListingPage(List<ListingView> items, long total, int page, int size) {
      this(items, total, page, size, null);
    }
  }

  /** One facet group: the axis plus its in-scope values, ordered count DESC then slug. */
  public record FacetGroup(String slug, String label, List<FacetValue> values) {}

  /**
   * One selectable value with its honest count. {@code selected} echoes the caller's own filter, so
   * the UI renders checked state from the response rather than re-parsing the URL.
   */
  public record FacetValue(String slug, String label, long count, boolean selected) {}

  /** Grammar caps (cause-naming 400s) — bounded so the facet fan-out stays ≤ 5 extra queries. */
  static final int MAX_FACET_ATTRIBUTES = 5;

  static final int MAX_FACET_VALUES_PER_ATTRIBUTE = 10;

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
      String ogImageVersion,
      // Commerce money config (V68, roadmap item 5) — lets the cart/checkout preview tax +
      // shipping honestly before placement. Not sensitive: both amounts render on every order
      // summary anyway.
      java.math.BigDecimal taxRate,
      java.math.BigDecimal shippingFee,
      /**
       * Whether this store has a live WhatsApp channel (slice B) — an ACTIVE {@code
       * org_whatsapp_config}. The portal's notification settings need it: without it that screen
       * cannot tell a store that never connected from one that did, and would have to offer a
       * WhatsApp opt-out to every shopper — a switch that, for most stores, turns off a channel
       * that could never send.
       *
       * <p>Not a leak: it says only <em>that</em> a channel exists, never the number, the WABA id
       * or anything sealed. A shopper learns the same fact the first time a message arrives.
       */
      boolean whatsappEnabled) {}

  /** v1 constants: both locales ship live; single-currency platform. */
  private static final List<String> SUPPORTED_LOCALES = List.of("ar", "en");

  private static final String CURRENCY_EGP = "EGP";

  // reads

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
        ogImageVersion,
        org.getTaxRate(),
        org.getShippingFee(),
        // One PK lookup on a table most orgs have no row in. Deliberately read here rather than
        // joined into resolveOrg: every other public read calls that too, and none of them needs
        // this.
        whatsAppConfigRepoFactory
            .create(rootDsl)
            .findByOrgId(org.getId())
            .map(OrgWhatsAppConfig::isActive)
            .orElse(false));
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

  // og-image (C2)

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

  // crawl feeds (stories/storefront_crawl_feeds.md)

  /**
   * The cap on listings in one {@link #crawlFeed}. With two locales that is 10 000 sitemap URLs,
   * comfortably inside the sitemap protocol's 50 000-per-file limit. Above it the feed reports
   * {@code truncated} with an honest {@code totalListings} rather than presenting a partial catalog
   * as complete — a deferred limit that announces itself is the difference between a known
   * constraint and a bug. No store is near it.
   */
  public static final int CRAWL_LISTING_CAP = 5_000;

  /** One page of the public store index. */
  public record StorefrontIndex(
      List<StorefrontCrawlRepository.StoreRef> data, long total, int page, int size) {}

  /**
   * The stores a crawler may index: active orgs holding at least one PUBLISHED listing, {@code slug
   * ASC}. Cross-org by definition — this is the one read on the public surface that is not scoped
   * to a single tenant, and the deliberate disclosure it carries (the platform's merchant list
   * becomes publicly readable) is the unavoidable cost of wanting those stores in a search index.
   * It exposes only what is already public: a slug and a catalog timestamp.
   */
  public StorefrontIndex indexableStores(int page, int size) {
    int offset = Pagination.offset(page, size);
    StorefrontCrawlRepository repo = crawlRepoFactory.create(rootDsl);
    return new StorefrontIndex(
        repo.findIndexableStores(offset, size), repo.countIndexableStores(), page, size);
  }

  /** One store's indexable URL set — everything a sitemap needs and nothing else. */
  public record CrawlFeed(
      List<StorefrontCrawlRepository.CrawlEntry> listings,
      List<StorefrontCrawlRepository.CrawlEntry> categories,
      List<StorefrontCrawlRepository.CrawlEntry> collections,
      List<StorefrontCrawlRepository.CrawlEntry> pages,
      boolean hasFeatured,
      long totalListings,
      boolean truncated) {}

  /**
   * Every URL of {@code orgSlug} worth indexing, with an honest {@code updated_at} per entity.
   * Categories and collections are filtered to those holding a PUBLISHED listing (the {@code
   * collections} rail's rule — an empty shelf is never advertised); {@code hasFeatured} is a
   * boolean rather than a count because the only question the client has is whether {@code
   * /featured} belongs in the sitemap at all.
   */
  public CrawlFeed crawlFeed(String orgSlug) {
    Org org = resolveOrg(orgSlug);
    UUID orgId = org.getId();
    StorefrontCrawlRepository repo = crawlRepoFactory.create(rootDsl);
    long total = repo.countPublishedListings(orgId);
    return new CrawlFeed(
        repo.publishedListings(orgId, CRAWL_LISTING_CAP),
        repo.nonEmptyCategories(orgId),
        repo.nonEmptyCollections(orgId),
        repo.pages(orgId),
        repo.hasFeatured(orgId),
        total,
        total > CRAWL_LISTING_CAP);
  }

  /**
   * Stream a listing's <b>primary</b> image (lowest {@code sort_order}) at a URL that never expires
   * — the {@link #ogImage} pattern applied per listing.
   *
   * <p><b>Why this exists.</b> Every catalog image URL we serve is a ~900s presigned GET, and a
   * third-party cache (a social scraper, a search engine's image index) holds a preview far longer
   * than that. Story 31 hit exactly this wall for {@code og:image} and had to fall back to the
   * <i>store</i> og image on product pages; structured data ({@code Product.image}) hits it too. So
   * the bytes are streamed, never redirected — a 302 gets its <i>target</i> cached by some
   * scrapers, which is the same bug one hop later.
   *
   * <p>PUBLISHED-only resolution, so a DRAFT or ARCHIVED listing's image is unreachable by
   * construction. Unknown listing, no image, or a storage failure are all the same opaque 404 (a
   * missing preview beats a hanging crawler).
   */
  public OgImage listingImage(String orgSlug, String listingSlug) {
    Org org = resolveOrg(orgSlug);
    ProductListing listing =
        listingRepoFactory
            .create(rootDsl)
            .findBySlugAndStatus(org.getId(), listingSlug, ListingStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("Listing not found: " + listingSlug));
    String key =
        listingRepoFactory.create(rootDsl).findImages(listing.getId()).stream()
            .min(Comparator.comparingInt(ProductListingImage::getSortOrder))
            .map(ProductListingImage::getObjectKey)
            .orElseThrow(() -> new NotFoundException("No listing image"));
    return ogImageSource
        .fetch(key)
        .map(f -> new OgImage(f.bytes(), f.contentType()))
        .orElseThrow(() -> new NotFoundException("Listing image unavailable"));
  }

  // banners (C1)

  /**
   * A public banner row — whitelisted for the anonymous storefront (customization epic slice C1).
   * {@code headline}/{@code subheading} are resolved to a <b>single</b> value by {@code ?locale=}
   * (slice L4 — this reverses the shipped C1 both-locales-client-resolve; the cache is now keyed
   * per (org, locale)); {@code imageUrl} is a short-lived presigned GET URL or null (→ gradient
   * slide); the target is the structured slug pair. Carries <b>no</b> id, org id, object key,
   * window bounds, or timestamps.
   */
  public record PublicBannerView(
      String headline, String subheading, String imageUrl, String targetType, String targetSlug) {}

  /** Locale-less overload — resolves to the org's default locale. */
  public List<PublicBannerView> banners(String orgSlug) {
    return banners(orgSlug, null);
  }

  /**
   * The anonymous home-banner carousel for {@code orgSlug}: the org's banners that are active,
   * in-window, and whose target still resolves (a category that exists, or a <b>PUBLISHED</b>
   * listing), {@code sort_order ASC}. The stale-target filtering is the repository's JOIN (epic §3)
   * — a banner pointing at an unpublished listing silently drops here and returns when it
   * re-publishes, with no edit from the merchant. Each row's {@code headline}/{@code subheading} is
   * resolved per field to {@code ?locale=} (requested → default → legacy paired column, slice L4).
   * 404 on unknown/inactive slug (opaque, via {@link #resolveOrg}); unknown locale → 400.
   */
  public List<PublicBannerView> banners(String orgSlug, String locale) {
    Org org = resolveOrg(orgSlug);
    UUID orgId = org.getId();
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(locale, defaultLocale);
    List<StorefrontBanner> rows =
        bannerRepoFactory.create(rootDsl).findPublicResolved(orgId, OffsetDateTime.now());
    Map<UUID, List<StorefrontBannerTranslation>> translations =
        bannerRepoFactory
            .create(rootDsl)
            .findTranslationsForBanners(rows.stream().map(StorefrontBanner::getId).toList());
    return rows.stream()
        .map(
            b -> {
              List<StorefrontBannerTranslation> ts = translations.get(b.getId());
              StorefrontBannerTranslation pref = pickLang(ts, resolvedLocale);
              StorefrontBannerTranslation def = pickLang(ts, defaultLocale);
              // Requested locale, else the default-locale row (guaranteed present by the write rule
              // + L1 backfill). The legacy paired-column fallback was dropped at L6.
              String headline =
                  coalesce(
                      pref == null ? null : pref.headline(), def == null ? null : def.headline());
              String subheading =
                  coalesce(
                      pref == null ? null : pref.subheading(),
                      def == null ? null : def.subheading());
              return new PublicBannerView(
                  headline,
                  subheading,
                  b.getImageObjectKey() == null ? null : storage.presignGet(b.getImageObjectKey()),
                  b.getTargetType().wire(),
                  b.getTargetSlug());
            })
        .toList();
  }

  private static StorefrontBannerTranslation pickLang(
      List<StorefrontBannerTranslation> ts, String lang) {
    if (ts == null) {
      return null;
    }
    for (StorefrontBannerTranslation t : ts) {
      if (t.language().equals(lang)) {
        return t;
      }
    }
    return null;
  }

  /**
   * Resolve a public org slug to its id through the same active-only, opaque-404 gate every other
   * public read uses (roadmap item 9). Exposed because the coupon preview is org-scoped but is not
   * a catalog read — it belongs to {@code CouponService}, which knows nothing about storefront
   * slugs. The slug→id boundary stays in one place rather than being re-implemented next to the
   * route.
   */
  public UUID profileOrgId(String orgSlug) {
    return resolveOrg(orgSlug).getId();
  }

  // collections (roadmap item 8)

  /**
   * A public collection row — the rail/nav shape, whitelisted to exactly {@code {slug, name}}. The
   * slug is the public handle ({@code /col/{slug}} and {@code ?collection=}); no internal id, sort
   * order, or membership size crosses. A merchant's shelf sizes are their business, not the
   * shopper's.
   */
  public record PublicCollectionView(String slug, String name) {}

  /**
   * The storefront's collections rail: the org's collections that hold at least one
   * <b>PUBLISHED</b> listing, in the merchant's rail order ({@code sort_order ASC, slug ASC}), each
   * name resolved {@code requested locale → default locale → slug}. The "at least one published"
   * rule lives in the SQL, so an all-drafts shelf is never advertised and the frontend rail
   * collapses for free — the same honesty posture as {@code ?sold=true} on the best-sellers strip.
   * 404 on an unknown/inactive org slug (opaque, via {@link #resolveOrg}); unknown locale → 400.
   */
  public List<PublicCollectionView> collections(String orgSlug, String locale) {
    Org org = resolveOrg(orgSlug);
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(locale, defaultLocale);
    return collectionRepoFactory
        .create(rootDsl)
        .findPublicRail(org.getId(), resolvedLocale, defaultLocale)
        .stream()
        .map(c -> new PublicCollectionView(c.getSlug(), c.getName()))
        .toList();
  }

  // checkout (B5)

  /**
   * One cart line at checkout: a public listing slug, an optional {@code variantKey}, and a
   * quantity. The key is the ONLY public handle for a variant (architecture §3) — no product id or
   * SKU is representable on this wire.
   */
  public record CheckoutLine(String listingSlug, String variantKey, int quantity) {
    /** Variant-less convenience — a listing sold as a single product (today's path). */
    public CheckoutLine(String listingSlug, int quantity) {
      this(listingSlug, null, quantity);
    }
  }

  /**
   * The anonymous checkout request: customer form + cart lines + optional notes + optional {@code
   * locale} (slice L2b — the language the order line's title is snapshotted in; unset → org
   * default, unknown → 400).
   */
  public record CheckoutInput(
      SalesOrderService.CustomerInput customer,
      List<CheckoutLine> lines,
      String notes,
      String locale,
      /**
       * The optional coupon code the shopper applied (roadmap item 9). Null/blank = no coupon,
       * which is every pre-V72 caller. Re-validated inside the placement txn, never trusted from
       * the preview — the slot can vanish between the two.
       */
      String couponCode) {
    /** Locale-less convenience (→ org default) — pre-L2b callers. */
    public CheckoutInput(
        SalesOrderService.CustomerInput customer, List<CheckoutLine> lines, String notes) {
      this(customer, lines, notes, null, null);
    }

    /** Coupon-less convenience — pre-V72 callers (every existing test and the locale-only path). */
    public CheckoutInput(
        SalesOrderService.CustomerInput customer,
        List<CheckoutLine> lines,
        String notes,
        String locale) {
      this(customer, lines, notes, locale, null);
    }
  }

  /**
   * A re-keyed shortage — slug + title, never {@code product_id}. Since VG2 it also names the
   * {@code variant} that fell short (null on a variant-less line), and the {@code title} is the
   * composed one ("Shirt — Red / M"), so a cart with two sizes of the same listing highlights the
   * line that is actually short instead of both.
   */
  public record StorefrontShortage(
      String listingSlug, String variant, String title, int requested, int available) {}

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
    // L2b: resolve the checkout locale (unknown → 400) so the order line's snapshotted title
    // matches
    // the language the shopper checked out in.
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(input.locale(), defaultLocale);

    // One query resolves all requested (deduplicated) slugs, PUBLISHED-only.
    LinkedHashSet<String> requestedSlugs = new LinkedHashSet<>();
    for (CheckoutLine line : input.lines()) {
      requestedSlugs.add(line.listingSlug());
    }
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);
    Map<String, CheckoutLineResolution> bySlug = new HashMap<>();
    for (CheckoutLineResolution r :
        listings.resolveForCheckout(
            orgId, requestedSlugs, ListingStatus.PUBLISHED, resolvedLocale, defaultLocale)) {
      bySlug.put(r.slug(), r);
    }
    ResolvedCart cart =
        resolveCart(listings, orgId, input.lines(), bySlug, resolvedLocale, defaultLocale);
    List<SalesOrderService.StorefrontLineInput> orderLines = cart.orderLines();
    Map<UUID, String> titleByProduct = cart.titleByProduct();
    Map<UUID, String> slugByProduct = cart.slugByProduct();
    Map<UUID, String> variantByProduct = cart.variantByProduct();

    try {
      SalesOrderService.StorefrontPlaced placed =
          salesOrderService.placeStorefrontOrder(
              orgId,
              input.customer(),
              orderLines,
              idempotencyKey,
              input.notes(),
              input.couponCode(),
              resolvedLocale,
              STOREFRONT_ACTOR);
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
                          variantByProduct.get(s.productId()),
                          titleByProduct.get(s.productId()),
                          s.requested(),
                          s.available()))
              .toList();
      throw new StorefrontOutOfStockException(shortages);
    }
  }

  /**
   * A resolved cart: the placement lines plus the per-<b>child-product</b> maps the response labels
   * and the 409 re-key need. Every map is keyed by the product actually reserved — the parent for a
   * variant-less line, the child for a variant one — which is what makes two variants of one
   * listing two independently-reportable lines.
   */
  record ResolvedCart(
      List<SalesOrderService.StorefrontLineInput> orderLines,
      Map<UUID, String> titleByProduct,
      Map<UUID, String> slugByProduct,
      Map<UUID, String> variantByProduct) {}

  /**
   * Resolve cart lines → placement lines, in request order (architecture §5 #2). Shared verbatim by
   * the anonymous and the portal checkout, so the two can never drift on what a variant line means.
   *
   * <p>The rules, all cause-naming:
   *
   * <ul>
   *   <li>unknown / non-PUBLISHED slug → opaque 404 (unchanged — never says which);
   *   <li>listing HAS variants and the line names none → <b>400</b>. Falling back to the parent
   *       would charge the listing price and reserve the parent's stock for an option the shopper
   *       never chose;
   *   <li>listing has NO variants and the line names one → 400 (the client is out of sync);
   *   <li>unknown or deactivated variant key → opaque 404, the same shape as an unknown slug.
   * </ul>
   *
   * <p>A variant line resolves to the child product, the variant's own price, and the composed
   * title ("Shirt — Red / M") that gets frozen onto the order line — the {@code
   * OrderLineTitleSnapshotIT} precedent, one level down.
   */
  static ResolvedCart resolveCart(
      ProductListingRepository listings,
      UUID orgId,
      List<CheckoutLine> lines,
      Map<String, CheckoutLineResolution> bySlug,
      String locale,
      String defaultLocale) {
    // Only fetch variants for the slugs that actually named one — a variant-less cart pays nothing.
    LinkedHashSet<String> variantSlugs = new LinkedHashSet<>();
    for (CheckoutLine line : lines) {
      if (line.variantKey() != null && !line.variantKey().isBlank()) {
        variantSlugs.add(line.listingSlug());
      }
    }
    Map<String, ProductListingRepository.PublicVariant> byVariantToken = new HashMap<>();
    if (!variantSlugs.isEmpty()) {
      for (ProductListingRepository.PublicVariant v :
          listings.findPublicVariantsForSlugs(
              orgId, variantSlugs, ListingStatus.PUBLISHED, locale, defaultLocale)) {
        byVariantToken.put(variantToken(v.listingSlug(), v.variantKey()), v);
      }
    }

    List<SalesOrderService.StorefrontLineInput> orderLines = new ArrayList<>(lines.size());
    Map<UUID, String> titleByProduct = new HashMap<>();
    Map<UUID, String> slugByProduct = new HashMap<>();
    Map<UUID, String> variantByProduct = new HashMap<>();
    for (CheckoutLine line : lines) {
      CheckoutLineResolution res = bySlug.get(line.listingSlug());
      if (res == null) {
        throw new NotFoundException("Listing not available");
      }
      String variantKey =
          line.variantKey() == null || line.variantKey().isBlank()
              ? null
              : line.variantKey().trim();

      if (variantKey == null) {
        if (res.hasVariants()) {
          throw new com.loai.inventory.common.exception.ValidationException(
              "variant is required for " + line.listingSlug());
        }
        orderLines.add(
            new SalesOrderService.StorefrontLineInput(
                res.productId(), line.quantity(), res.salesPrice(), res.title()));
        titleByProduct.put(res.productId(), res.title());
        slugByProduct.put(res.productId(), res.slug());
        continue;
      }

      if (!res.hasVariants()) {
        throw new com.loai.inventory.common.exception.ValidationException(
            line.listingSlug() + " has no variants");
      }
      ProductListingRepository.PublicVariant variant =
          byVariantToken.get(variantToken(line.listingSlug(), variantKey));
      if (variant == null) {
        // Unknown or deactivated — the same opaque 404 as an unknown slug, so the endpoint never
        // becomes an oracle for which options a merchant has retired.
        throw new NotFoundException("Listing not available");
      }
      String composedTitle = res.title() + " — " + variant.label();
      orderLines.add(
          new SalesOrderService.StorefrontLineInput(
              variant.productId(), line.quantity(), variant.salesPrice(), composedTitle));
      titleByProduct.put(variant.productId(), composedTitle);
      slugByProduct.put(variant.productId(), res.slug());
      variantByProduct.put(variant.productId(), variant.variantKey());
    }
    return new ResolvedCart(orderLines, titleByProduct, slugByProduct, variantByProduct);
  }

  /**
   * The public composite token for a variant — {@code "{slug}::{variantKey}"}. One spelling, used
   * by both the availability batch's request grammar and the cart-resolution lookup, so the two can
   * never disagree about what identifies a variant.
   */
  public static String variantToken(String slug, String variantKey) {
    return slug + VARIANT_TOKEN_SEPARATOR + variantKey;
  }

  /** The separator in a {@code slug::variantKey} availability token. */
  public static final String VARIANT_TOKEN_SEPARATOR = "::";

  // availability (B2)

  /**
   * One availability row, keyed by the <b>token the caller asked with</b> — a bare {@code slug} or
   * a {@code slug::variantKey} (VG2). Echoing the request token rather than re-deriving it is what
   * lets a client zip the response onto its cart lines without re-parsing anything.
   */
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
    Org org = resolveOrg(orgSlug);
    UUID orgId = org.getId();
    String defaultLocale = defaultLocaleOf(org);
    LinkedHashSet<String> ordered = new LinkedHashSet<>(slugs);

    // VG2: a token is either a bare slug (listing-level = "any active variant or the parent has
    // stock", today's meaning preserved) or "slug::variantKey" (that one option). Both resolve in
    // the same two queries; the response stays one row per requested token, in request order.
    LinkedHashSet<String> listingSlugs = new LinkedHashSet<>();
    LinkedHashSet<String> variantSlugs = new LinkedHashSet<>();
    for (String token : ordered) {
      String slug = listingSlugOf(token);
      listingSlugs.add(slug);
      if (variantKeyOf(token) != null) {
        variantSlugs.add(slug);
      }
    }

    ProductListingRepository listings = listingRepoFactory.create(rootDsl);
    Map<String, Integer> availableBySlug = new HashMap<>();
    for (ProductListingRepository.ListingAvailability a :
        listings.resolveAvailability(orgId, listingSlugs, ListingStatus.PUBLISHED)) {
      availableBySlug.put(a.slug(), a.available());
    }
    Map<String, Integer> availableByVariant = new HashMap<>();
    if (!variantSlugs.isEmpty()) {
      for (ProductListingRepository.PublicVariant v :
          listings.findPublicVariantsForSlugs(
              orgId, variantSlugs, ListingStatus.PUBLISHED, defaultLocale, defaultLocale)) {
        availableByVariant.put(variantToken(v.listingSlug(), v.variantKey()), v.available());
      }
    }

    List<AvailabilityView> out = new ArrayList<>(ordered.size());
    for (String token : ordered) {
      Integer available =
          variantKeyOf(token) == null
              ? availableBySlug.get(listingSlugOf(token))
              // An unknown or deactivated variant is `false`, exactly like an unknown slug —
              // opaque,
              // never a 404, never an oracle for which options exist.
              : availableByVariant.get(token);
      out.add(new AvailabilityView(token, available != null && available > 0));
    }
    return out;
  }

  /** The listing half of an availability token ({@code "shirt::red-m"} → {@code "shirt"}). */
  private static String listingSlugOf(String token) {
    int at = token.indexOf(VARIANT_TOKEN_SEPARATOR);
    return at < 0 ? token : token.substring(0, at);
  }

  /** The variant half, or null for a bare listing token. A trailing {@code "::"} reads as null. */
  private static String variantKeyOf(String token) {
    int at = token.indexOf(VARIANT_TOKEN_SEPARATOR);
    if (at < 0) {
      return null;
    }
    String key = token.substring(at + VARIANT_TOKEN_SEPARATOR.length());
    return key.isBlank() ? null : key;
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
        orgSlug, categorySlug, q, minPrice, maxPrice, sort, featured, null, null, page, size);
  }

  /** Filtered read without the {@code sold} narrow (roadmap item 4) — every listing, any sort. */
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
    return listPublished(
        orgSlug, categorySlug, q, minPrice, maxPrice, sort, featured, null, locale, page, size);
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
   *
   * <p>Best-sellers (roadmap item 4, {@code stories/storefront_best_sellers.md}): {@code
   * sort=best_selling} ranks by units actually sold in a rolling 30-day window (never-sold listings
   * last, never hidden), and the separate {@code sold=true} predicate narrows to listings that
   * genuinely sold in that window. Both are computed in SQL so they compose with paging; neither
   * puts a sold count on the wire — the ordering is the fact.
   */
  public ListingPage listPublished(
      String orgSlug,
      String categorySlug,
      String q,
      String minPrice,
      String maxPrice,
      String sort,
      String featured,
      String sold,
      String locale,
      int page,
      int size) {
    return listPublished(
        orgSlug,
        categorySlug,
        q,
        minPrice,
        maxPrice,
        sort,
        featured,
        sold,
        locale,
        Map.of(),
        null,
        page,
        size);
  }

  /**
   * The facets-aware read (roadmap item 7, {@code stories/storefront_attribute_facets.md}).
   *
   * <p>{@code attributeFilters} is the parsed {@code attr_{slug}=v1,v2} grammar: OR within an
   * attribute, AND across them, ANDed with every B3 filter. Malformed shapes are cause-naming 400s
   * here; an <b>unknown</b> attribute or value slug is deliberately NOT an error — it resolves to
   * the empty set (the category-filter convention), because a merchant deleting an attribute must
   * not turn every stale bookmarked URL into an error wall.
   *
   * <p>{@code includeFacets} is opt-in and strictly {@code "true"} or absent (anything else → 400,
   * the same no-silent-coercion discipline as {@code ?featured=}). Only the catalog page pays for
   * the counts, so the cache-key space stays small and the strips keep the lean envelope.
   */
  public ListingPage listPublished(
      String orgSlug,
      String categorySlug,
      String q,
      String minPrice,
      String maxPrice,
      String sort,
      String featured,
      String sold,
      String locale,
      Map<String, List<String>> attributeFilters,
      String includeFacets,
      int page,
      int size) {
    return listPublished(
        orgSlug,
        categorySlug,
        null,
        q,
        minPrice,
        maxPrice,
        sort,
        featured,
        sold,
        locale,
        attributeFilters,
        includeFacets,
        page,
        size);
  }

  /**
   * The collections-aware read (roadmap item 8, {@code stories/storefront_collections.md}).
   *
   * <p>{@code collectionSlug} is one more optional predicate, ANDed with the whole B3 grammar
   * (q/price/category/featured/sold/{@code attr_*}/paging). Two rules make it behave like the rest
   * of the family: when it is present and no explicit {@code ?sort=} was given, the default order
   * becomes the merchant's curated position inside that collection (an explicit sort still
   * overrides — the grammar stays uniform); and an <b>unknown</b> slug resolves to the <b>empty
   * result, not a 404</b>, the category-filter convention, so a stale bookmark or a renamed
   * collection renders the designed empty landing page instead of an error wall. A
   * blank-but-present value is still a 400 — that is a caller bug, not a stale link.
   */
  public ListingPage listPublished(
      String orgSlug,
      String categorySlug,
      String collectionSlug,
      String q,
      String minPrice,
      String maxPrice,
      String sort,
      String featured,
      String sold,
      String locale,
      Map<String, List<String>> attributeFilters,
      String includeFacets,
      int page,
      int size) {
    int offset = Pagination.offset(page, size);
    Map<String, List<String>> attrs = normalizeAttributeFilters(attributeFilters);
    boolean wantFacets = parseIncludeFacets(includeFacets);

    String query = trimToNull(q);
    boolean featuredOnly = parseFeatured(featured);
    boolean soldOnly = parseSold(sold);
    String wantedCollection = parseCollection(collectionSlug);
    // featured / a collection + no explicit sort → curated order; otherwise the usual grammar
    // (blank = NEWEST). A collection's own curated order wins over featured's when both are asked
    // for: the shopper navigated to a named shelf, so that shelf's arrangement is the one they
    // mean.
    String sortTrim = trimToNull(sort);
    ListingSort listingSort =
        sortTrim == null
            ? (wantedCollection != null
                ? ListingSort.COLLECTION
                : (featuredOnly ? ListingSort.FEATURED : ListingSort.NEWEST))
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

    UUID collectionId = null;
    if (wantedCollection != null) {
      collectionId =
          collectionRepoFactory
              .create(rootDsl)
              .findBySlug(orgId, wantedCollection)
              .map(com.loai.inventory.domain.model.Collection::getId)
              .orElse(null);
      if (collectionId == null) {
        // Unknown collection → the empty page, never a 404. Short-circuited rather than passed
        // through as a null predicate, because a null id would also drop the narrow entirely and
        // serve the whole catalog under a stale shelf's name — the one wrong answer here.
        return new ListingPage(List.of(), 0L, page, size, wantFacets ? List.of() : null);
      }
    }

    final UUID narrowedCollectionId = collectionId;
    List<ProductListing> rows =
        listings.findByFilters(
            orgId,
            ListingStatus.PUBLISHED,
            categoryId,
            narrowedCollectionId,
            query,
            resolvedLocale,
            defaultLocale,
            min,
            max,
            featuredOnly,
            soldOnly,
            attrs,
            listingSort,
            offset,
            size);
    long total =
        listings.countByFilters(
            orgId,
            ListingStatus.PUBLISHED,
            categoryId,
            narrowedCollectionId,
            query,
            resolvedLocale,
            defaultLocale,
            min,
            max,
            featuredOnly,
            soldOnly,
            attrs);

    List<FacetGroup> facets =
        wantFacets
            ? buildFacets(
                listings,
                orgId,
                categoryId,
                narrowedCollectionId,
                query,
                resolvedLocale,
                defaultLocale,
                min,
                max,
                featuredOnly,
                soldOnly,
                attrs)
            : null;

    return new ListingPage(
        enrich(rows, orgId, listings, resolvedLocale, defaultLocale), total, page, size, facets);
  }

  /**
   * Group the repository's flat {@code (attribute, value, count)} rows into the response shape:
   * attributes ordered by slug, values by <b>count DESC then slug</b> (the useful choices first),
   * attributes with no in-scope value omitted entirely — an empty group is chrome that tells the
   * shopper nothing.
   */
  private List<FacetGroup> buildFacets(
      ProductListingRepository listings,
      UUID orgId,
      UUID categoryId,
      UUID collectionId,
      String query,
      String resolvedLocale,
      String defaultLocale,
      java.math.BigDecimal min,
      java.math.BigDecimal max,
      boolean featuredOnly,
      boolean soldOnly,
      Map<String, List<String>> attrs) {
    List<ProductListingRepository.FacetCount> counts =
        listings.facetCounts(
            orgId,
            ListingStatus.PUBLISHED,
            categoryId,
            collectionId,
            query,
            resolvedLocale,
            defaultLocale,
            min,
            max,
            featuredOnly,
            soldOnly,
            attrs);

    Map<String, String> labelByAttribute = new java.util.TreeMap<>();
    Map<String, List<FacetValue>> valuesByAttribute = new java.util.TreeMap<>();
    for (ProductListingRepository.FacetCount fc : counts) {
      if (fc.count() <= 0) {
        continue;
      }
      labelByAttribute.putIfAbsent(fc.attributeSlug(), fc.attributeLabel());
      boolean selected = attrs.getOrDefault(fc.attributeSlug(), List.of()).contains(fc.valueSlug());
      valuesByAttribute
          .computeIfAbsent(fc.attributeSlug(), k -> new ArrayList<>())
          .add(new FacetValue(fc.valueSlug(), fc.valueLabel(), fc.count(), selected));
    }

    List<FacetGroup> groups = new ArrayList<>(valuesByAttribute.size());
    valuesByAttribute.forEach(
        (attributeSlug, values) -> {
          values.sort(
              java.util.Comparator.comparingLong(FacetValue::count)
                  .reversed()
                  .thenComparing(FacetValue::slug));
          groups.add(new FacetGroup(attributeSlug, labelByAttribute.get(attributeSlug), values));
        });
    return groups;
  }

  /**
   * Validate + canonicalize the {@code attr_*} grammar. Blank slugs, empty value lists, and the
   * caps are 400s naming the parameter; values are trimmed, lower-cased and de-duplicated so {@code
   * attr_size=M,m} is one selection, not two.
   *
   * <p>The attribute <b>name</b> is lower-cased on the same principle, which means {@code
   * attr_Size} and {@code attr_size} are one attribute — so their values are <b>folded
   * together</b>, exactly as the servlet already folds a repeated same-case parameter. Overwriting
   * instead (the first cut) silently dropped half a shopper's selection with no 400 to tell them.
   * The caps are therefore applied to the <b>collapsed</b> map: six spellings of one axis is one
   * filter, not six, and counting spellings turned that into a bogus "at most 5 attr_* filters".
   */
  private static Map<String, List<String>> normalizeAttributeFilters(
      Map<String, List<String>> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, LinkedHashSet<String>> folded = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : raw.entrySet()) {
      String attribute =
          e.getKey() == null ? null : e.getKey().trim().toLowerCase(java.util.Locale.ROOT);
      if (attribute == null || attribute.isBlank()) {
        throw new com.loai.inventory.common.exception.ValidationException(
            "attr_ filter name must not be blank");
      }
      LinkedHashSet<String> values = folded.computeIfAbsent(attribute, k -> new LinkedHashSet<>());
      for (String v : e.getValue() == null ? List.<String>of() : e.getValue()) {
        String value = v == null ? null : v.trim().toLowerCase(java.util.Locale.ROOT);
        if (value != null && !value.isBlank()) {
          values.add(value);
        }
      }
    }
    if (folded.size() > MAX_FACET_ATTRIBUTES) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "at most " + MAX_FACET_ATTRIBUTES + " attr_* filters");
    }
    Map<String, List<String>> out = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, LinkedHashSet<String>> e : folded.entrySet()) {
      LinkedHashSet<String> values = e.getValue();
      if (values.isEmpty()) {
        throw new com.loai.inventory.common.exception.ValidationException(
            "attr_" + e.getKey() + " must name at least one value");
      }
      if (values.size() > MAX_FACET_VALUES_PER_ATTRIBUTE) {
        throw new com.loai.inventory.common.exception.ValidationException(
            "attr_"
                + e.getKey()
                + " accepts at most "
                + MAX_FACET_VALUES_PER_ATTRIBUTE
                + " values");
      }
      out.put(e.getKey(), List.copyOf(values));
    }
    return out;
  }

  /**
   * {@code include_facets} is exactly {@code "true"} or absent — anything else is a 400. Case
   * <b>sensitive</b>, like the sibling {@code sold} / {@code featured} parsers on this same read:
   * one endpoint should not answer the same wire value two ways depending on which parameter
   * carries it.
   */
  private static boolean parseIncludeFacets(String raw) {
    String value = trimToNull(raw);
    if (value == null) {
      return false;
    }
    if (!"true".equals(value)) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "include_facets must be 'true' when present (was '" + raw + "')");
    }
    return true;
  }

  /**
   * Turn a page of PUBLISHED listing rows into card {@link ListingView}s, batching every decoration
   * so the cost stays four queries regardless of page size (no N+1): the primary image, stock
   * availability, the APPROVED-review aggregate, and the per-language content.
   *
   * <p>Shared by the catalog read and the portal wishlist read, which is the point — a saved item
   * must render as exactly the same card as the catalog one, down to the absent-not-zero rating and
   * the quantity-free {@code in_stock} boolean. The returned list preserves {@code rows}' order, so
   * the caller owns the ordering (catalog: the SQL sort; wishlist: save recency).
   */
  private List<ListingView> enrich(
      List<ProductListing> rows,
      UUID orgId,
      ProductListingRepository listings,
      String resolvedLocale,
      String defaultLocale) {
    List<UUID> listingIds = rows.stream().map(ProductListing::getId).toList();
    // One batched image query for the whole page, and the grid presigns only each listing's primary
    // image — full galleries and category breadcrumbs are a detail-view concern.
    Map<UUID, ProductListingImage> primaryByListing = new HashMap<>();
    for (ProductListingImage img : listings.findImagesForListings(listingIds)) {
      // Ordered by sort_order asc, so the first one seen per listing is its primary image.
      primaryByListing.putIfAbsent(img.getListingId(), img);
    }
    // Availability by the page's product_ids (B2), mirroring the image batch. Untracked/absent →
    // not in the map → not in stock.
    Map<UUID, Integer> availableByProduct =
        inventoryRepoFactory
            .create(rootDsl)
            .findAvailableByProductIds(
                orgId, rows.stream().map(ProductListing::getProductId).toList());
    // And the APPROVED-review aggregate for the page (R1, epic §8) — one grouped query, absent
    // when a listing has no approved review (never a fabricated zero).
    Map<UUID, ListingReviewRepository.Aggregate> aggregateByListing =
        reviewRepoFactory.create(rootDsl).findAggregates(orgId, listingIds);
    // Batch the per-language content and resolve each listing to the requested locale (L2).
    Map<UUID, List<ProductListingTranslation>> translationsByListing =
        listings.findTranslationsForListings(listingIds);
    // VG2: one grouped query gives the page its "from" prices and the children half of the §4
    // in_stock union. A listing absent from this map simply has no variants — the row is unchanged.
    Map<UUID, ProductListingRepository.VariantSummary> variantSummaries =
        listings.findVariantSummaries(orgId, listingIds);
    return rows.stream()
        .map(
            l -> {
              ProductListingImage primary = primaryByListing.get(l.getId());
              List<PublicImage> images =
                  primary == null ? List.of() : List.of(toPublicImage(primary));
              ProductListingRepository.VariantSummary variants = variantSummaries.get(l.getId());
              // §4: once a listing has active variants the PARENT is unbuyable by construction —
              // checkout rejects a variant-less line for it — so its stock says nothing about
              // whether anything on this page can be bought, and a union over it advertises a dead
              // end. Variants present ⇒ the answer is the children's; absent ⇒ it is the parent's.
              boolean inStock =
                  variants == null
                      ? availableByProduct.getOrDefault(l.getProductId(), 0) > 0
                      : variants.anyInStock();
              // With variants, the row shows the honest "from" price — the cheapest active option —
              // not the parent's sales_price, which nothing on the page is actually sold at.
              java.math.BigDecimal price =
                  variants == null || variants.minPrice() == null
                      ? l.getSalesPrice()
                      : variants.minPrice();
              ListingReviewRepository.Aggregate agg = aggregateByListing.get(l.getId());
              ResolvedContent content =
                  resolveContent(
                      translationsByListing.get(l.getId()), resolvedLocale, defaultLocale);
              return new ListingView(
                  l.getSlug(),
                  content.title(),
                  content.marketingCopy(),
                  price,
                  inStock,
                  images,
                  List.of(),
                  formatRatingAvg(agg),
                  agg == null ? null : agg.count(),
                  variants != null);
            })
        .toList();
  }

  /**
   * The PUBLISHED-only read behind the portal wishlist (roadmap item 3, {@code
   * stories/customer_wishlist.md}): resolve saved listing ids to fully-enriched card views, in the
   * <b>given id order</b> (the wishlist's save recency), dropping any id that is not currently
   * PUBLISHED.
   *
   * <p>Dropping rather than erroring is the contract: a saved listing that the merchant unpublishes
   * simply stops appearing and returns on republish — the saved row is never destroyed behind the
   * customer's back, and the wishlist page never shows an item a shopper cannot buy.
   *
   * <p>Takes {@code orgId} rather than a slug because the caller is the portal, whose session
   * principal already carries the org — there is no slug to resolve and no second lookup to pay
   * for.
   */
  public List<ListingView> publishedViewsByIds(UUID orgId, List<UUID> ids, String locale) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Org org =
        orgRepoFactory
            .create(rootDsl)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org not found: " + orgId));
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(locale, defaultLocale);
    ProductListingRepository listings = listingRepoFactory.create(rootDsl);

    Map<UUID, ProductListing> byId =
        listings.findPublishedByIds(orgId, ids).stream()
            .collect(java.util.stream.Collectors.toMap(ProductListing::getId, l -> l));
    // Re-impose the caller's order on the set the query returned (SQL's IN says nothing about it),
    // and drop the ids that no longer resolve as PUBLISHED.
    List<ProductListing> rows =
        ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    return enrich(rows, orgId, listings, resolvedLocale, defaultLocale);
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
                            catTranslations.get(c.getId()), resolvedLocale, defaultLocale),
                        c.getSlug()))
            .toList();
    // VG2: the active variants, with per-variant price + stock and locale-resolved option labels.
    List<VariantView> variants =
        toVariantViews(
            listings.findPublicVariantsForListing(
                orgId, listing.getId(), resolvedLocale, defaultLocale));
    // §4: with active variants the listing's in_stock is theirs alone. The parent is unbuyable on
    // a has-variants listing (checkout rejects a variant-less line), so folding its stock in would
    // send the shopper to a picker where every option is disabled.
    boolean parentInStock =
        inventoryRepoFactory
                .create(rootDsl)
                .findAvailableByProductIds(orgId, List.of(listing.getProductId()))
                .getOrDefault(listing.getProductId(), 0)
            > 0;
    boolean inStock =
        variants.isEmpty() ? parentInStock : variants.stream().anyMatch(VariantView::inStock);
    ResolvedContent content =
        resolveContent(listings.findTranslations(listing.getId()), resolvedLocale, defaultLocale);
    return toView(listings, listing, inStock, categories, content, variants);
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
                    resolveCategoryName(translations.get(c.getId()), resolvedLocale, defaultLocale),
                    c.getSlug(),
                    c.getParentCategoryId() == null ? null : slugById.get(c.getParentCategoryId())))
        .toList();
  }

  // helpers

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
      case "best_selling" -> ListingSort.BEST_SELLING;
      default ->
          throw new com.loai.inventory.common.exception.ValidationException(
              "Parameter 'sort' must be one of: newest, price_asc, price_desc, best_selling");
    };
  }

  /**
   * Parse the {@code sold} param (roadmap item 4, {@code stories/storefront_best_sellers.md}):
   * absent/blank → false; exactly {@code true} → true; anything else → 400 — the same
   * no-silent-coercion rule as {@code featured}. It narrows to listings with ≥1 unit sold in the
   * ranking window, and exists for the home strip's honesty guard (a store with no sales renders an
   * empty result the strip collapses on, rather than a fabricated "best sellers" list). The
   * <b>sort</b> alone never narrows — a sort must not shrink "N results".
   */
  private static boolean parseSold(String raw) {
    String t = trimToNull(raw);
    if (t == null) {
      return false;
    }
    if ("true".equals(t)) {
      return true;
    }
    throw new com.loai.inventory.common.exception.ValidationException(
        "Parameter 'sold' must be 'true' or absent");
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

  /**
   * Parse the {@code collection} param (roadmap item 8): absent → null (no narrow); otherwise the
   * trimmed, lower-cased slug. A <b>present but blank</b> value is a 400 — a caller that sent
   * {@code ?collection=} meant to narrow and got it wrong, which is different from a stale slug
   * that no longer resolves (that is the empty result, decided at resolution time, not here).
   */
  private static String parseCollection(String raw) {
    if (raw == null) {
      return null;
    }
    String t = raw.trim();
    if (t.isEmpty()) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "Parameter 'collection' must name a collection slug when present");
    }
    return t.toLowerCase(java.util.Locale.ROOT);
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
      ResolvedContent content,
      List<VariantView> variants) {
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
        agg == null ? null : agg.count(),
        !variants.isEmpty(),
        variants);
  }

  /** Repository variant rows → the whitelisted public shape (no product id, no SKU, no barcode). */
  private static List<VariantView> toVariantViews(
      List<ProductListingRepository.PublicVariant> variants) {
    return variants.stream()
        .map(
            v ->
                new VariantView(
                    v.variantKey(), v.label(), v.options(), v.salesPrice(), v.available() > 0))
        .toList();
  }

  // locale resolution (content-localization slice L2)

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
   * default locale's (never null for {@code title} — a default-locale row is guaranteed by the
   * write rule + L1 backfill). {@code marketingCopy} falls back the same way, so a
   * present-but-empty locale copy shows the default's. (The legacy {@code product_listing} column
   * fallback was dropped at L6 — the translation table is the only source.)
   */
  private static ResolvedContent resolveContent(
      List<ProductListingTranslation> translations, String preferred, String defaultLocale) {
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
    String title = coalesce(pref == null ? null : pref.title(), def == null ? null : def.title());
    String copy =
        coalesce(
            pref == null ? null : pref.marketingCopy(), def == null ? null : def.marketingCopy());
    return new ResolvedContent(title, copy);
  }

  private static String coalesce(String a, String b) {
    return a != null ? a : b;
  }

  /**
   * Resolve a category's name to one locale (L3): the requested locale's row, else the default
   * locale's — never null (a default-locale row is guaranteed). The legacy {@code category.name}
   * fallback was dropped at L6.
   */
  private static String resolveCategoryName(
      List<CategoryTranslation> translations, String preferred, String defaultLocale) {
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
    return coalesce(pref, def);
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
