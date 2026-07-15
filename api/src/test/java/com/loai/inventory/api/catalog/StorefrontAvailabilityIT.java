package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.AvailabilityView;
import com.loai.inventory.service.StorefrontService.ListingView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
 * The availability signal ({@code stories/storefront_availability_signal.md}, B2): {@code in_stock}
 * on listing reads and the batch {@code availability} resolver — a boolean, never a quantity, and a
 * {@code false} that is opaque across out-of-stock / untracked / not-PUBLISHED. Drives {@link
 * StorefrontService} directly.
 */
@Testcontainers
class StorefrontAvailabilityIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService storefront;
  static ObjectStorage storage;
  static final ObjectMapper JSON = ObjectMapperProvider.build();

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
    cfg.setMaximumPoolSize(4);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    storage = ObjectStorageFactory.build();
    storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
            storage,
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
            + " inventory, product, org RESTART IDENTITY CASCADE");
  }

  @Test
  void listAndDetail_carryInStock_perAvailability() {
    UUID org = createOrg("acme");
    UUID inStockP = createProduct(org, "A");
    UUID soldOutP = createProduct(org, "B");
    UUID untrackedP = createProduct(org, "C");
    publish(org, inStockP, "in-stock");
    publish(org, soldOutP, "sold-out");
    publish(org, untrackedP, "untracked");
    inventory(org, inStockP, 5, 0); // available 5
    inventory(org, soldOutP, 3, 3); // available 0
    // untrackedP has no inventory row.

    var page = storefront.listPublished("acme", null, 0, 20);
    assertEquals(3, page.total());
    assertEquals(true, inStockOf(page.items(), "in-stock"));
    assertEquals(false, inStockOf(page.items(), "sold-out"));
    assertEquals(false, inStockOf(page.items(), "untracked"));

    assertTrue(storefront.getListing("acme", "in-stock").inStock());
    assertFalse(storefront.getListing("acme", "sold-out").inStock());
    assertFalse(storefront.getListing("acme", "untracked").inStock());
  }

  @Test
  void availabilityBatch_preservesOrder_opaqueFalseForInvalid() {
    UUID org = createOrg("acme");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    UUID d = createProduct(org, "D");
    publish(org, a, "in-stock");
    publish(org, b, "sold-out");
    draft(org, d, "draft-item");
    inventory(org, a, 5, 0);
    inventory(org, b, 2, 2);

    List<AvailabilityView> out =
        storefront.availability("acme", List.of("in-stock", "sold-out", "draft-item", "unknown"));

    assertEquals(4, out.size());
    assertEquals("in-stock", out.get(0).slug());
    assertTrue(out.get(0).inStock());
    assertFalse(out.get(1).inStock(), "sold-out");
    // A DRAFT and an unknown slug are both false — indistinguishable from sold-out.
    assertFalse(out.get(2).inStock(), "draft is opaque false");
    assertFalse(out.get(3).inStock(), "unknown is opaque false");
    assertEquals("draft-item", out.get(2).slug());
    assertEquals("unknown", out.get(3).slug());
  }

  @Test
  void noQuantity_orProductId_inSerializedListing() throws Exception {
    UUID org = createOrg("acme");
    UUID p = createProduct(org, "A");
    publish(org, p, "widget");
    inventory(org, p, 7, 2);

    var page = storefront.listPublished("acme", null, 0, 20);
    String json =
        JSON.writeValueAsString(page.items().stream().map(PublicListingResponse::from).toList());
    assertTrue(json.contains("in_stock"));
    for (String forbidden :
        new String[] {"stock_qty", "reserved_qty", "available", "product_id", p.toString()}) {
      assertFalse(json.contains(forbidden), "leaked: " + forbidden);
    }
  }

  private static boolean inStockOf(List<ListingView> items, String slug) {
    return items.stream().filter(i -> i.slug().equals(slug)).findFirst().orElseThrow().inStock();
  }

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, slug).set(ORG.SLUG, slug).execute();
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

  private void publish(UUID org, UUID product, String slug) {
    insertListing(org, product, slug, ListingStatus.PUBLISHED);
  }

  private void draft(UUID org, UUID product, String slug) {
    insertListing(org, product, slug, ListingStatus.DRAFT);
  }

  private void insertListing(UUID org, UUID product, String slug, ListingStatus status) {
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ID, UUID.randomUUID())
        .set(PRODUCT_LISTING.ORG_ID, org)
        .set(PRODUCT_LISTING.PRODUCT_ID, product)
        .set(PRODUCT_LISTING.TITLE, slug + " title")
        .set(PRODUCT_LISTING.SLUG, slug)
        .set(PRODUCT_LISTING.SALES_PRICE, new BigDecimal("12.00"))
        .set(PRODUCT_LISTING.STATUS, status)
        .set(PRODUCT_LISTING.PUBLISHED_AT, OffsetDateTime.now())
        .execute();
  }

  private void inventory(UUID org, UUID product, int stock, int reserved) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stock)
        .set(INVENTORY.RESERVED_QTY, reserved)
        .execute();
  }
}
