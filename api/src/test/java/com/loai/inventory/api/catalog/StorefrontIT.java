package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.CATEGORY_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.ListingPage;
import com.loai.inventory.service.StorefrontService.ListingView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * The public storefront read model: only PUBLISHED listings are visible, a draft slug 404s,
 * unknown/inactive orgs 404, the category filter works, and the category nav resolves parent slugs.
 * Drives {@link StorefrontService} directly (presigning is offline, so no MinIO needed).
 */
@Testcontainers
class StorefrontIT {

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
            storage,
            // Read-only tests never call checkout(), so the placement engine is unused here.
            null,
            null);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE product_listing_image, product_listing_category, product_listing, category,"
            + " product, org RESTART IDENTITY CASCADE");
  }

  @Test
  void onlyPublishedListingsAreVisible() {
    Seed s = seed("acme", true);
    publishedListing(s, "live-notebook", "cat-notebooks");
    draftListing(s, "wip-notebook");

    ListingPage page = service.listPublished(s.slug, null, 0, 20);
    assertEquals(1, page.total());
    assertEquals("live-notebook", page.items().get(0).slug());
    // Grid items carry images but not category breadcrumbs.
    assertTrue(
        page.items().get(0).categories() == null || page.items().get(0).categories().isEmpty());
    assertEquals(1, page.items().get(0).images().size());
  }

  @Test
  void draftListing_is404_evenByDirectSlug() {
    Seed s = seed("acme", true);
    draftListing(s, "wip-notebook");
    assertThrows(NotFoundException.class, () -> service.getListing(s.slug, "wip-notebook"));
  }

  @Test
  void publishedDetail_hasCategoriesAndImages() {
    Seed s = seed("acme", true);
    publishedListing(s, "live-notebook", "cat-notebooks");

    ListingView v = service.getListing(s.slug, "live-notebook");
    assertEquals("live-notebook", v.slug());
    assertEquals(1, v.categories().size());
    assertEquals("cat-notebooks", v.categories().get(0).slug());
    assertEquals(1, v.images().size());
    assertTrue(v.images().get(0).url().startsWith("http"));
  }

  @Test
  void categoryFilter_returnsOnlyThatCategory() {
    Seed s = seed("acme", true);
    publishedListing(s, "live-notebook", "cat-notebooks");
    // A second published listing in a different category.
    UUID otherCat = category(s.orgId, "Pens", "cat-pens");
    UUID l2 = insertListing(s.orgId, s.productId2, "live-pen", ListingStatus.PUBLISHED);
    listingRepo().replaceCategories(l2, Set.of(otherCat));

    assertEquals(1, service.listPublished(s.slug, "cat-notebooks", 0, 20).total());
    assertEquals(1, service.listPublished(s.slug, "cat-pens", 0, 20).total());
    assertEquals(2, service.listPublished(s.slug, null, 0, 20).total());
  }

  @Test
  void unknownCategorySlug_is404() {
    Seed s = seed("acme", true);
    assertThrows(NotFoundException.class, () -> service.listPublished(s.slug, "ghost", 0, 20));
  }

  @Test
  void unknownOrgSlug_is404() {
    assertThrows(NotFoundException.class, () -> service.listPublished("nope", null, 0, 20));
  }

  @Test
  void inactiveOrg_is404() {
    Seed s = seed("dormant", false);
    publishedListing(s, "live-notebook", "cat-notebooks");
    assertThrows(NotFoundException.class, () -> service.listPublished(s.slug, null, 0, 20));
  }

  @Test
  void categoryNav_resolvesParentSlug() {
    Seed s = seed("acme", true);
    UUID parent = category(s.orgId, "Notebooks", "notebooks");
    Category child = new Category();
    child.setOrgId(s.orgId);
    child.setParentCategoryId(parent);
    child.setName("Spiral");
    child.setSlug("spiral");
    categoryRepo().insert(child);

    var nav = service.listCategories(s.slug);
    assertEquals(2, nav.size());
    var spiral = nav.stream().filter(n -> n.slug().equals("spiral")).findFirst().orElseThrow();
    assertEquals("notebooks", spiral.parentSlug());
    var notebooks =
        nav.stream().filter(n -> n.slug().equals("notebooks")).findFirst().orElseThrow();
    assertNull(notebooks.parentSlug());
  }

  // ───────── adversarial: review findings ─────────

  /**
   * Finding #1 — pagination offset overflow. {@code page * size} was computed in {@code int}. The
   * adversarial value below is the nasty one: 67_108_864 * 64 == 2^32, which an int multiply wraps
   * to exactly 0 — so before the fix this *silently* returned page 0 (the first listing) as if the
   * caller had asked for it, with no error at all. Now it must be rejected as out-of-range.
   */
  @Test
  void overflowingPage_isRejected_notSilentlyServedAsPageZero() {
    Seed s = seed("acme", true);
    publishedListing(s, "live-notebook", "cat-notebooks");

    // 67_108_864 * 64 wraps to 0 in int; 1_100_000_000 * 2 wraps negative. Both must 400, not 200.
    assertThrows(
        ValidationException.class, () -> service.listPublished(s.slug, null, 67_108_864, 64));
    assertThrows(
        ValidationException.class, () -> service.listPublished(s.slug, null, 1_100_000_000, 2));

    // Sanity: a genuinely empty far page (within range) is an empty result, never an error.
    assertEquals(0, service.listPublished(s.slug, null, 5, 20).items().size());
  }

  /**
   * Finding #2 — category nav mis-rooted past the first 1000. The parent is the oldest row, then
   * &gt;1000 newer fillers, then the child. The old {@code findAll(orgId, 0, 1000)} window (1000
   * newest by created_at desc) excluded the parent, so the child's parent slug resolved to null and
   * it rendered as a root. The full-org fetch must resolve it correctly and drop nothing.
   */
  @Test
  void categoryNav_resolvesParent_evenBeyondThe1000Window() {
    Seed s = seed("acme", true);
    OffsetDateTime t0 = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    // Parent is the single oldest category.
    UUID parentId = insertCategoryAt(s.orgId, null, "Notebooks", "notebooks", t0);
    // 1001 fillers, all newer than the parent — they fill (and overflow) the old 1000 window.
    for (int i = 0; i < 1001; i++) {
      insertCategoryAt(s.orgId, null, "Filler " + i, "filler-" + i, t0.plusSeconds(i + 1L));
    }
    // Child is the newest row; it is inside any recent window but its parent is not.
    insertCategoryAt(s.orgId, parentId, "Spiral", "spiral", t0.plusSeconds(5000));

    var nav = service.listCategories(s.slug);

    assertEquals(1003, nav.size(), "nav must include every category, not just the newest 1000");
    var spiral = nav.stream().filter(n -> n.slug().equals("spiral")).findFirst().orElseThrow();
    assertEquals("notebooks", spiral.parentSlug(), "deep parent must resolve, not null");
  }

  /**
   * Finding #3 — N+1 and over-presigning on the grid. A listing with 3 images: the grid (list) view
   * must carry only the primary (sort_order 0) image, while the detail view still carries all
   * three. A second listing with no images proves the in-memory grouping handles the empty case.
   */
  @Test
  void grid_carriesOnlyPrimaryImage_detailCarriesAll() {
    Seed s = seed("acme", true);
    UUID withImages = insertListing(s.orgId, s.productId, "gallery", ListingStatus.PUBLISHED);
    insertImage(s.orgId, withImages, "c.png", "third", 2);
    insertImage(s.orgId, withImages, "a.png", "first", 0);
    insertImage(s.orgId, withImages, "b.png", "second", 1);
    // A second published listing with zero images.
    insertListing(s.orgId, s.productId2, "bare", ListingStatus.PUBLISHED);

    ListingPage page = service.listPublished(s.slug, null, 0, 20);
    assertEquals(2, page.total());

    ListingView gallery =
        page.items().stream().filter(i -> i.slug().equals("gallery")).findFirst().orElseThrow();
    assertEquals(1, gallery.images().size(), "grid shows only the primary image");
    assertEquals(0, gallery.images().get(0).sortOrder(), "and it is the sort_order=0 one");

    ListingView bare =
        page.items().stream().filter(i -> i.slug().equals("bare")).findFirst().orElseThrow();
    assertTrue(bare.images().isEmpty(), "a listing with no images yields an empty image list");

    // The detail view is unchanged — it still presigns the full gallery.
    assertEquals(3, service.getListing(s.slug, "gallery").images().size());
  }

  // ───────── seeding helpers ─────────

  private record Seed(UUID orgId, String slug, UUID productId, UUID productId2) {}

  private Seed seed(String name, boolean active) {
    UUID orgId = UUID.randomUUID();
    String slug = name + "-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, name)
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
    return categoryRepo().insert(c).getId();
  }

  /**
   * Insert a category with an explicit created_at so the >1000-window ordering is deterministic.
   */
  private UUID insertCategoryAt(
      UUID orgId, UUID parentId, String name, String slug, OffsetDateTime createdAt) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CATEGORY)
        .set(CATEGORY.ID, id)
        .set(CATEGORY.ORG_ID, orgId)
        .set(CATEGORY.PARENT_CATEGORY_ID, parentId)
        .set(CATEGORY.SLUG, slug)
        .set(CATEGORY.CREATED_AT, createdAt)
        .set(CATEGORY.UPDATED_AT, createdAt)
        .execute();
    // L6: the category name lives in the per-language translation table (both locales the same
    // here,
    // so the nav resolves it whatever the org's default_locale is).
    for (String lang : new String[] {"ar", "en"}) {
      dsl.insertInto(CATEGORY_TRANSLATION)
          .set(CATEGORY_TRANSLATION.CATEGORY_ID, id)
          .set(CATEGORY_TRANSLATION.LANGUAGE, lang)
          .set(CATEGORY_TRANSLATION.NAME, name)
          .execute();
    }
    return id;
  }

  private void insertImage(UUID orgId, UUID listingId, String file, String alt, int sortOrder) {
    ProductListingImage img = new ProductListingImage();
    img.setOrgId(orgId);
    img.setListingId(listingId);
    img.setObjectKey(ObjectStorage.keyPrefix(orgId, listingId) + file);
    img.setAltText(alt);
    img.setSortOrder(sortOrder);
    listingRepo().insertImage(img);
  }

  /** A PUBLISHED listing with one category and one image. */
  private void publishedListing(Seed s, String slug, String categorySlug) {
    UUID listingId = insertListing(s.orgId, s.productId, slug, ListingStatus.PUBLISHED);
    UUID catId = category(s.orgId, "Notebooks", categorySlug);
    listingRepo().replaceCategories(listingId, Set.of(catId));
    ProductListingImage img = new ProductListingImage();
    img.setOrgId(s.orgId);
    img.setListingId(listingId);
    img.setObjectKey(ObjectStorage.keyPrefix(s.orgId, listingId) + "hero.png");
    img.setAltText("hero");
    img.setSortOrder(0);
    listingRepo().insertImage(img);
  }

  private void draftListing(Seed s, String slug) {
    insertListing(s.orgId, s.productId2, slug, ListingStatus.DRAFT);
  }

  private UUID insertListing(UUID orgId, UUID productId, String slug, ListingStatus status) {
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

  private ProductListingRepository listingRepo() {
    return new ProductListingRepositoryFactoryImpl().create(dsl);
  }

  private CategoryRepository categoryRepo() {
    return new CategoryRepositoryFactoryImpl().create(dsl);
  }
}
