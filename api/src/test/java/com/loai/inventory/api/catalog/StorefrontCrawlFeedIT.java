package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.STOREFRONT_PAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.StorefrontCrawlRepository;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.StorefrontService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The anonymous crawl surface ({@code stories/storefront_crawl_feeds.md}): which stores a crawler
 * may index, each store's indexable URL set, and the stable per-listing image.
 *
 * <p>The membership rules are the point of most of these — a store with nothing published, a
 * category holding only drafts, and a suspended tenant must all be *absent*, because advertising
 * them spends a crawler's budget on a page that tells a shopper nothing.
 */
@Testcontainers
class StorefrontCrawlFeedIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService service;
  static ObjectStorage storage;
  static FakeOgImageSource source;

  /** Keyed bytes, empty for anything else (a storage miss → 404) — PublicOgImageIT's double. */
  static final class FakeOgImageSource implements com.loai.inventory.service.OgImageSource {
    final java.util.Map<String, Fetched> byKey = new java.util.HashMap<>();

    @Override
    public java.util.Optional<Fetched> fetch(String objectKey) {
      return java.util.Optional.ofNullable(byKey.get(objectKey));
    }
  }

  @BeforeAll
  static void startInfra() {
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(8);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    storage = ObjectStorageFactory.build();
    source = new FakeOgImageSource();

    service =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CollectionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontCrawlRepositoryFactoryImpl(),
            storage,
            null,
            source);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE storefront_page, product_listing_image, product_listing_category,"
            + " product_listing, category, product, org RESTART IDENTITY CASCADE");
  }

  // the store index

  @Test
  void storeIndex_listsOnlyActiveOrgsWithAPublishedListing() {
    Seed live = seed("live", true);
    published(live, "kettle");

    Seed draftsOnly = seed("drafts", true);
    listing(draftsOnly.orgId, draftsOnly.productId, "wip", ListingStatus.DRAFT);

    Seed suspended = seed("suspended", false);
    published(suspended, "hidden-but-published");

    seed("empty", true); // no listings at all

    List<String> slugs =
        service.indexableStores(0, 20).data().stream()
            .map(StorefrontCrawlRepository.StoreRef::slug)
            .toList();

    assertEquals(1, slugs.size(), "only the live store is indexable");
    assertEquals(live.slug, slugs.get(0));
    assertEquals(1, service.indexableStores(0, 20).total());
  }

  @Test
  void storeIndex_isAlphabeticalAndPaged() {
    for (String name : List.of("c", "a", "b")) {
      Seed s = seedWithSlug(name, true);
      published(s, name + "-item");
    }

    assertEquals(
        List.of("a", "b"),
        service.indexableStores(0, 2).data().stream()
            .map(StorefrontCrawlRepository.StoreRef::slug)
            .toList());
    assertEquals(
        List.of("c"),
        service.indexableStores(1, 2).data().stream()
            .map(StorefrontCrawlRepository.StoreRef::slug)
            .toList());
    assertEquals(3, service.indexableStores(0, 2).total(), "total is the whole set, not the page");
  }

  @Test
  void storeIndex_catalogTimestampIsTheNewestPublishedListing() {
    Seed s = seed("acme", true);
    OffsetDateTime old = OffsetDateTime.parse("2024-01-01T00:00:00Z");
    OffsetDateTime recent = OffsetDateTime.parse("2026-06-01T00:00:00Z");
    stampedListing(s.orgId, s.productId, "old", ListingStatus.PUBLISHED, old);
    stampedListing(s.orgId, s.productId2, "new", ListingStatus.PUBLISHED, recent);

    StorefrontCrawlRepository.StoreRef ref = service.indexableStores(0, 20).data().get(0);
    assertEquals(recent.toInstant(), ref.catalogUpdatedAt().toInstant());
  }

  @Test
  void storeIndex_rejectsABadPage() {
    assertThrows(ValidationException.class, () -> service.indexableStores(-1, 20));
    assertThrows(ValidationException.class, () -> service.indexableStores(0, 101));
  }

  // the per-store feed

  @Test
  void feed_carriesOnlyPublishedAndNonEmptyEntities() {
    Seed s = seed("acme", true);
    UUID liveListing = published(s, "kettle");
    listing(s.orgId, s.productId2, "wip", ListingStatus.DRAFT);

    UUID stocked = category(s.orgId, "Kitchen", "kitchen");
    listingRepo().replaceCategories(liveListing, Set.of(stocked));
    category(s.orgId, "Empty", "empty-shelf"); // no listing points at it

    page(s.orgId, "about");

    StorefrontService.CrawlFeed feed = service.crawlFeed(s.slug);

    assertEquals(List.of("kettle"), keys(feed.listings()));
    assertEquals(List.of("kitchen"), keys(feed.categories()), "an empty category is thin content");
    assertEquals(List.of("about"), keys(feed.pages()), "only the kinds that exist");
    assertEquals(1, feed.totalListings());
    assertFalse(feed.truncated());
    assertFalse(feed.hasFeatured());
  }

  @Test
  void feed_reportsFeaturedOnlyWhenSomethingIsPinned() {
    Seed s = seed("acme", true);
    UUID id = published(s, "kettle");
    assertFalse(service.crawlFeed(s.slug).hasFeatured());

    dsl.update(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.FEATURED_SORT, 0)
        .where(PRODUCT_LISTING.ID.eq(id))
        .execute();
    assertTrue(service.crawlFeed(s.slug).hasFeatured());
  }

  @Test
  void feed_lastmodIsTheRowsOwnTimestampAndIsNeverSynthesised() {
    Seed s = seed("acme", true);
    OffsetDateTime stamped = OffsetDateTime.parse("2025-03-04T05:06:07Z");
    stampedListing(s.orgId, s.productId, "kettle", ListingStatus.PUBLISHED, stamped);

    StorefrontCrawlRepository.CrawlEntry e = service.crawlFeed(s.slug).listings().get(0);
    assertEquals(stamped.toInstant(), e.updatedAt().toInstant());
  }

  @Test
  void feed_unknownOrInactiveOrgIsAnOpaque404() {
    Seed suspended = seed("suspended", false);
    published(suspended, "kettle");

    assertThrows(NotFoundException.class, () -> service.crawlFeed("no-such-store"));
    assertThrows(NotFoundException.class, () -> service.crawlFeed(suspended.slug));
  }

  // the stable listing image

  @Test
  void listingImage_resolvesThePrimaryImageOfAPublishedListing() {
    Seed s = seed("acme", true);
    UUID id = listing(s.orgId, s.productId, "kettle", ListingStatus.PUBLISHED);
    image(s.orgId, id, "second.png", 5);
    image(s.orgId, id, "hero.png", 0);

    StorefrontService.OgImage img = service.listingImage(s.slug, "kettle");
    assertNotNull(img.bytes());
    assertNotNull(img.contentType());
  }

  @Test
  void listingImage_isAnOpaque404ForDraftUnknownAndImageless() {
    Seed s = seed("acme", true);
    UUID draft = listing(s.orgId, s.productId, "wip", ListingStatus.DRAFT);
    image(s.orgId, draft, "hero.png", 0);
    listing(s.orgId, s.productId2, "bare", ListingStatus.PUBLISHED); // published, no image

    assertThrows(NotFoundException.class, () -> service.listingImage(s.slug, "wip"));
    assertThrows(NotFoundException.class, () -> service.listingImage(s.slug, "ghost"));
    assertThrows(NotFoundException.class, () -> service.listingImage(s.slug, "bare"));
  }

  // guards this slice added, and the guarantee it deliberately did not spend

  @Test
  void reservedSlugsAreRefused() {
    for (String reserved : List.of("orders", "unsubscribe", "storefronts")) {
      ValidationException e =
          assertThrows(ValidationException.class, () -> OrgService.validateSlug(reserved));
      assertTrue(e.getMessage().toLowerCase().contains("reserved"), e.getMessage());
    }
    OrgService.validateSlug("order-store"); // a near-miss is fine
  }

  /**
   * The whitelist this slice deliberately did NOT widen. A crawler wanting timestamps was the
   * obvious reason to add one to the catalog DTO; a separate feed exists precisely so that
   * guarantee survives, and it is worth re-asserting from the branch that had the motive.
   */
  @Test
  void publicListingResponseStillCarriesNoTimestamp() {
    for (java.lang.reflect.Field f :
        com.loai.inventory.api.dto.PublicListingResponse.class.getDeclaredFields()) {
      if (f.isSynthetic()) continue;
      String lower = f.getName().toLowerCase();
      assertFalse(
          lower.contains("updated") || lower.contains("created") || lower.contains("published"),
          "PublicListingResponse must stay timestamp-free; found " + f.getName());
    }
  }

  // fixtures

  private record Seed(UUID orgId, String slug, UUID productId, UUID productId2) {}

  private Seed seed(String name, boolean active) {
    return seedWithSlug(name + "-" + UUID.randomUUID(), active);
  }

  private Seed seedWithSlug(String slug, boolean active) {
    UUID orgId = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, active)
        .execute();
    return new Seed(orgId, slug, product(orgId, "P1"), product(orgId, "P2"));
  }

  private UUID product(UUID orgId, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, sku)
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private UUID category(UUID orgId, String name, String slug) {
    Category c = new Category();
    c.setOrgId(orgId);
    c.setName(name);
    c.setSlug(slug);
    return new CategoryRepositoryFactoryImpl().create(dsl).insert(c).getId();
  }

  private void page(UUID orgId, String kind) {
    dsl.insertInto(STOREFRONT_PAGE)
        .set(STOREFRONT_PAGE.ID, UUID.randomUUID())
        .set(STOREFRONT_PAGE.ORG_ID, orgId)
        .set(STOREFRONT_PAGE.KIND, kind)
        .set(STOREFRONT_PAGE.UPDATED_AT, OffsetDateTime.now())
        .execute();
  }

  /** A PUBLISHED listing with one image. */
  private UUID published(Seed s, String slug) {
    UUID id = listing(s.orgId, s.productId, slug, ListingStatus.PUBLISHED);
    image(s.orgId, id, "hero.png", 0);
    return id;
  }

  private UUID listing(UUID orgId, UUID productId, String slug, ListingStatus status) {
    ProductListing l = new ProductListing();
    l.setOrgId(orgId);
    l.setProductId(productId);
    l.setTitle(slug + " title");
    l.setMarketingCopy("copy");
    l.setSlug(slug);
    l.setSalesPrice(new BigDecimal("19.99"));
    l.setStatus(status);
    l.setPublishedAt(status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null);
    return listingRepo().insert(l).getId();
  }

  /** A listing whose {@code updated_at} is forced, so lastmod assertions are deterministic. */
  private UUID stampedListing(
      UUID orgId, UUID productId, String slug, ListingStatus status, OffsetDateTime at) {
    UUID id = listing(orgId, productId, slug, status);
    dsl.update(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.UPDATED_AT, at)
        .where(PRODUCT_LISTING.ID.eq(id))
        .execute();
    return id;
  }

  private void image(UUID orgId, UUID listingId, String file, int sortOrder) {
    ProductListingImage img = new ProductListingImage();
    img.setOrgId(orgId);
    img.setListingId(listingId);
    img.setObjectKey(ObjectStorage.keyPrefix(orgId, listingId) + file);
    img.setAltText("alt");
    img.setSortOrder(sortOrder);
    listingRepo().insertImage(img);
    source.byKey.put(
        img.getObjectKey(),
        new com.loai.inventory.service.OgImageSource.Fetched(new byte[] {1, 2, 3}, "image/png"));
  }

  private static List<String> keys(List<StorefrontCrawlRepository.CrawlEntry> entries) {
    return entries.stream().map(StorefrontCrawlRepository.CrawlEntry::key).toList();
  }

  private ProductListingRepository listingRepo() {
    return new ProductListingRepositoryFactoryImpl().create(dsl);
  }
}
