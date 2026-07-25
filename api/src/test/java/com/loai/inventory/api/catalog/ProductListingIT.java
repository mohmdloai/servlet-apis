package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductListingService.ImageView;
import com.loai.inventory.service.ProductListingService.ListingView;
import com.loai.inventory.service.ProductListingService.PresignResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * ProductListing lifecycle ({@code DRAFT→PUBLISHED→ARCHIVED}), one-listing-per-product and slug
 * uniqueness, the listing⇄category many-to-many, and presigned image attach/list/remove (with the
 * cross-tenant key guard). Presigning is offline, so no MinIO is needed.
 */
@Testcontainers
class ProductListingIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ProductListingService service;
  static CategoryService categoryService;

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

    service =
        new ProductListingService(
            dsl,
            new ProductListingRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl(),
            ObjectStorageFactory.build());
    categoryService =
        new CategoryService(
            dsl,
            new CategoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE product_listing_image, product_listing_category, product_listing, category,"
            + " product, org RESTART IDENTITY CASCADE");
  }

  // ───────── lifecycle ─────────

  @Test
  void lifecycle_publish_unpublish_archive() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "Widget", "Nice", "widget", price("19.99"));
    assertEquals(ListingStatus.DRAFT, l.getStatus());
    assertNull(l.getPublishedAt());

    ProductListing published = service.publish(org, l.getId());
    assertEquals(ListingStatus.PUBLISHED, published.getStatus());
    assertNotNull(published.getPublishedAt());

    ProductListing unpublished = service.unpublish(org, l.getId());
    assertEquals(ListingStatus.DRAFT, unpublished.getStatus());
    assertNull(unpublished.getPublishedAt());

    ProductListing archived = service.archive(org, l.getId());
    assertEquals(ListingStatus.ARCHIVED, archived.getStatus());
  }

  @Test
  void illegalTransitions_conflict() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "W", null, "w", price("5"));

    // unpublish/archive-twice from the wrong state.
    assertThrows(ConflictException.class, () -> service.unpublish(org, l.getId())); // DRAFT
    service.publish(org, l.getId());
    assertThrows(
        ConflictException.class, () -> service.publish(org, l.getId())); // already PUBLISHED
    service.archive(org, l.getId());
    assertThrows(
        ConflictException.class, () -> service.archive(org, l.getId())); // already ARCHIVED
  }

  @Test
  void onlyPublishedCountTowardStatusFilter() {
    UUID org = createOrg("acme");
    ProductListing a = service.create(org, createProduct(org, "A"), "A", null, "a", price("1"));
    service.create(org, createProduct(org, "B"), "B", null, "b", price("1"));
    service.publish(org, a.getId());

    assertEquals(2, service.count(org, null));
    assertEquals(1, service.count(org, ListingStatus.PUBLISHED));
    assertEquals(1, service.getAll(org, ListingStatus.PUBLISHED, 0, 10).size());
  }

  @Test
  void oneListingPerProduct_andUniqueSlug() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    service.create(org, product, "W", null, "w", price("5"));
    assertThrows(
        ConflictException.class, () -> service.create(org, product, "W2", null, "w2", price("5")));

    UUID product2 = createProduct(org, "SKU2");
    assertThrows(
        ConflictException.class, () -> service.create(org, product2, "W3", null, "w", price("5")));
  }

  @Test
  void create_forMissingProduct_isRejected() {
    UUID org = createOrg("acme");
    assertThrows(
        ValidationException.class,
        () -> service.create(org, UUID.randomUUID(), "W", null, "w", price("5")));
  }

  // ───────── categories ─────────

  @Test
  void setCategories_replacesSet_andValidatesExistence() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "W", null, "w", price("5"));
    Category c1 = categoryService.create(org, "C1", "c1", null);
    Category c2 = categoryService.create(org, "C2", "c2", null);

    service.setCategories(org, l.getId(), Set.of(c1.getId(), c2.getId()));
    ListingView view = service.getById(org, l.getId());
    assertEquals(2, view.categoryIds().size());
    assertTrue(view.categoryIds().containsAll(List.of(c1.getId(), c2.getId())));

    // Replace with just one.
    service.setCategories(org, l.getId(), Set.of(c1.getId()));
    assertEquals(List.of(c1.getId()), service.getById(org, l.getId()).categoryIds());

    // Unknown category id is rejected.
    assertThrows(
        ValidationException.class,
        () -> service.setCategories(org, l.getId(), Set.of(UUID.randomUUID())));
  }

  @Test
  void deletingListing_cascadesCategoriesAndImages() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "W", null, "w", price("5"));
    Category c1 = categoryService.create(org, "C1", "c1", null);
    service.setCategories(org, l.getId(), Set.of(c1.getId()));
    attach(org, l.getId());

    service.delete(org, l.getId());

    assertEquals(
        0,
        dsl.fetchCount(
            dsl.selectFrom(PRODUCT_LISTING_CATEGORY)
                .where(PRODUCT_LISTING_CATEGORY.LISTING_ID.eq(l.getId()))));
    assertEquals(
        0,
        dsl.fetchCount(
            dsl.selectFrom(PRODUCT_LISTING_IMAGE)
                .where(PRODUCT_LISTING_IMAGE.LISTING_ID.eq(l.getId()))));
  }

  // ───────── images ─────────

  @Test
  void presign_attach_list_remove() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "W", null, "w", price("5"));

    PresignResult presign = service.presignImageUpload(org, l.getId(), "photo.png", "image/png");
    assertTrue(presign.uploadUrl().startsWith("http"));
    assertTrue(presign.objectKey().startsWith(org + "/listings/" + l.getId() + "/"));

    ImageView img = service.attachImage(org, l.getId(), presign.objectKey(), "alt", 0);
    assertNotNull(img.url());

    List<ImageView> images = service.listImages(org, l.getId());
    assertEquals(1, images.size());

    service.removeImage(org, l.getId(), img.id());
    assertTrue(service.listImages(org, l.getId()).isEmpty());
  }

  @Test
  void attach_withForeignKeyPrefix_isRejected() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "W", null, "w", price("5"));
    // A key that does not belong to this org+listing.
    String foreign = UUID.randomUUID() + "/listings/" + UUID.randomUUID() + "/x.png";
    assertThrows(
        ValidationException.class, () -> service.attachImage(org, l.getId(), foreign, null, 0));
  }

  @Test
  void images_orderedBySortOrder() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "W", null, "w", price("5"));

    attachAt(org, l.getId(), 2);
    attachAt(org, l.getId(), 0);
    attachAt(org, l.getId(), 1);

    List<ImageView> images = service.listImages(org, l.getId());
    assertEquals(List.of(0, 1, 2), images.stream().map(ImageView::sortOrder).toList());
  }

  @Test
  void updateImage_editsAltAndReorders() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "W", null, "w", price("5"));
    PresignResult p = service.presignImageUpload(org, l.getId(), "hero.png", "image/png");
    ImageView img = service.attachImage(org, l.getId(), p.objectKey(), null, 0);

    ImageView updated = service.updateImage(org, l.getId(), img.id(), "hero shot", 5);
    assertEquals("hero shot", updated.altText());
    assertEquals(5, updated.sortOrder());

    ImageView reread = service.listImages(org, l.getId()).get(0);
    assertEquals("hero shot", reread.altText());
    assertEquals(5, reread.sortOrder());

    assertThrows(
        NotFoundException.class,
        () -> service.updateImage(org, l.getId(), UUID.randomUUID(), "x", 0));
  }

  // ───────── list enrichment ─────────

  @Test
  void getAll_enrichesRowsWithCategoriesAndImages() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    ProductListing l = service.create(org, product, "Widget", null, "widget", price("5"));
    Category c1 = categoryService.create(org, "C1", "c1", null);
    Category c2 = categoryService.create(org, "C2", "c2", null);
    service.setCategories(org, l.getId(), Set.of(c1.getId(), c2.getId()));
    attachAt(org, l.getId(), 1);
    attachAt(org, l.getId(), 0);

    // A second listing with neither categories nor images stays lean (absent from both batches).
    UUID product2 = createProduct(org, "SKU2");
    ProductListing bare = service.create(org, product2, "Bare", null, "bare", price("5"));

    List<ListingView> views = service.getAll(org, null, 0, 10);
    assertEquals(2, views.size());

    ListingView enriched =
        views.stream().filter(v -> v.listing().getId().equals(l.getId())).findFirst().orElseThrow();
    assertEquals(2, enriched.categoryIds().size());
    assertTrue(enriched.categoryIds().containsAll(List.of(c1.getId(), c2.getId())));
    // Images batch-loaded in sort order (thumbnail = images.get(0)).
    assertEquals(List.of(0, 1), enriched.images().stream().map(ImageView::sortOrder).toList());

    ListingView lean =
        views.stream()
            .filter(v -> v.listing().getId().equals(bare.getId()))
            .findFirst()
            .orElseThrow();
    assertTrue(lean.categoryIds().isEmpty());
    assertTrue(lean.images().isEmpty());
  }

  // ───────── helpers ─────────

  private void attach(UUID org, UUID listingId) {
    attachAt(org, listingId, 0);
  }

  private void attachAt(UUID org, UUID listingId, int sortOrder) {
    PresignResult p = service.presignImageUpload(org, listingId, "f" + sortOrder + ".png", null);
    service.attachImage(org, listingId, p.objectKey(), null, sortOrder);
  }

  private static BigDecimal price(String v) {
    return new BigDecimal(v);
  }

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }
}
