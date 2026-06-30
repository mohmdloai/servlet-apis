package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
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
            storage);
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
