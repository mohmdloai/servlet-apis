package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicBannerResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.service.StorefrontBannerService;
import com.loai.inventory.service.StorefrontBannerService.BannerInput;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.PublicBannerView;
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
 * The anonymous banner read (customization epic slice C1): only active, in-window, target-resolving
 * banners serve, {@code sort_order ASC}; the stale-target degradation is reversible; the public row
 * is whitelisted (no id/org/key/window/timestamps); and the schema carries no price/discount column
 * (the honesty rule, asserted structurally). Drives {@link StorefrontService#banners} and {@link
 * StorefrontBannerService} directly. See {@code stories/storefront_banners.md}.
 */
@Testcontainers
class PublicBannersIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService storefront;
  static StorefrontBannerService banners;
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
    cfg.setMaximumPoolSize(8);
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
            new StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
            storage,
            null,
            null);
    banners =
        new StorefrontBannerService(
            dsl,
            new StorefrontBannerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
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
        "TRUNCATE storefront_banner, product_listing, category, product, org RESTART IDENTITY"
            + " CASCADE");
  }

  // AC6: only active, in-window, resolving — in sort order

  @Test
  void servesOnlyActiveInWindowResolving_inSortOrder() {
    UUID org = insertOrg("acme", "ar", true);
    insertCategory(org, "kitchen");
    UUID product = insertProduct(org, "SKU1");
    insertListing(org, product, "kettle", ListingStatus.PUBLISHED);
    OffsetDateTime now = OffsetDateTime.now();

    banners.create(org, input("first", "category", "kitchen")); // sort 0, visible
    banners.create(org, input("second", "listing", "kettle")); // sort 1, visible
    banners.create(org, inactive("hidden-inactive", "category", "kitchen")); // inactive → hidden
    banners.create(
        org, windowed("expired", "category", "kitchen", null, now.minusDays(1))); // ended → hidden
    banners.create(
        org,
        windowed("scheduled", "category", "kitchen", now.plusDays(1), null)); // future → hidden

    List<PublicBannerView> served = storefront.banners("acme");
    assertEquals(
        List.of("first", "second"), served.stream().map(PublicBannerView::headline).toList());
  }

  @Test
  void unpublishedListingTarget_disappears_reappearsOnRepublish() {
    UUID org = insertOrg("acme", "ar", true);
    UUID product = insertProduct(org, "SKU1");
    UUID listing = insertListing(org, product, "kettle", ListingStatus.PUBLISHED);
    banners.create(org, input("deal", "listing", "kettle"));

    assertEquals(1, storefront.banners("acme").size());

    // Merchant unpublishes the target → the banner silently drops (no edit required).
    setListingStatus(listing, ListingStatus.DRAFT);
    assertTrue(storefront.banners("acme").isEmpty());

    // Re-publish → it returns.
    setListingStatus(listing, ListingStatus.PUBLISHED);
    assertEquals(1, storefront.banners("acme").size());
  }

  @Test
  void deletedCategoryTarget_dropsBanner() {
    UUID org = insertOrg("acme", "ar", true);
    UUID cat = insertCategory(org, "kitchen");
    banners.create(org, input("shop-kitchen", "category", "kitchen"));
    assertEquals(1, storefront.banners("acme").size());

    dsl.deleteFrom(CATEGORY).where(CATEGORY.ID.eq(cat)).execute();
    assertTrue(storefront.banners("acme").isEmpty());
  }

  // AC7 (L4-updated): whitelist + single locale-resolved value

  @Test
  void publicRow_isWhitelisted_singleValueResolvedByLocale() throws Exception {
    UUID org = insertOrg("acme", "ar", true);
    insertCategory(org, "kitchen");
    banners.create(
        org,
        new BannerInput(
            "عرض",
            "Sale",
            "تخفيضات",
            "Big discounts",
            null,
            "category",
            "kitchen",
            true,
            null,
            null));

    // L4: en request resolves to the English copy; ar (default) to the Arabic; unset → default.
    assertEquals("Sale", storefront.banners("acme", "en").get(0).headline());
    assertEquals("Big discounts", storefront.banners("acme", "en").get(0).subheading());
    assertEquals("عرض", storefront.banners("acme", "ar").get(0).headline());
    assertEquals("عرض", storefront.banners("acme").get(0).headline());

    List<PublicBannerResponse> data =
        storefront.banners("acme", "en").stream().map(PublicBannerResponse::from).toList();
    String json = JSON.writeValueAsString(data);

    // Single resolved values + the structured target — the paired _ar/_en keys are gone (L4).
    assertTrue(json.contains("headline"), json);
    assertTrue(json.contains("subheading"), json);
    assertTrue(json.contains("target_type"), json);
    assertTrue(json.contains("target_slug"), json);
    assertFalse(json.contains("headline_ar"), json);
    assertFalse(json.contains("headline_en"), json);
    assertFalse(json.contains("subheading_ar"), json);
    assertFalse(json.contains("subheading_en"), json);
    // No internal field leaks (JSON keys — the image-less row can't smuggle a key via a URL).
    for (String forbidden :
        new String[] {
          "\"id\"",
          "org_id",
          "image_object_key",
          "sort_order",
          "\"active\"",
          "starts_at",
          "ends_at",
          "created_at",
          "updated_at"
        }) {
      assertFalse(json.contains(forbidden), "leaked: " + forbidden + " in " + json);
    }
  }

  @Test
  void unsupportedLocale_is400() {
    UUID org = insertOrg("acme", "ar", true);
    insertCategory(org, "kitchen");
    banners.create(org, input("عرض", "category", "kitchen"));
    assertThrows(
        com.loai.inventory.common.exception.ValidationException.class,
        () -> storefront.banners("acme", "fr"));
  }

  // AC8: no price/discount column (structural honesty rule)

  @Test
  void schema_hasNoPriceOrDiscountColumn() {
    List<String> columns =
        dsl.fetch(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_name = 'storefront_banner'")
            .getValues("column_name", String.class);
    assertFalse(columns.isEmpty(), "table should have columns");
    for (String col : columns) {
      String c = col.toLowerCase();
      assertFalse(
          c.contains("price")
              || c.contains("discount")
              || c.contains("countdown")
              || c.contains("was_")
              || c.contains("percent"),
          "unexpected promotion column: " + col);
    }
  }

  // opaque 404

  @Test
  void unknownOrInactiveOrg_is404() {
    insertOrg("suspended", "ar", false);
    assertThrows(NotFoundException.class, () -> storefront.banners("suspended"));
    assertThrows(NotFoundException.class, () -> storefront.banners("ghost"));
  }

  // fixtures

  private static BannerInput input(String headlineAr, String targetType, String targetSlug) {
    return new BannerInput(
        headlineAr, null, null, null, null, targetType, targetSlug, true, null, null);
  }

  private static BannerInput inactive(String headlineAr, String targetType, String targetSlug) {
    return new BannerInput(
        headlineAr, null, null, null, null, targetType, targetSlug, false, null, null);
  }

  private static BannerInput windowed(
      String headlineAr,
      String targetType,
      String targetSlug,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt) {
    return new BannerInput(
        headlineAr, null, null, null, null, targetType, targetSlug, true, startsAt, endsAt);
  }

  private UUID insertOrg(String slug, String defaultLocale, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, active)
        .set(ORG.DEFAULT_LOCALE, defaultLocale)
        .execute();
    return id;
  }

  private UUID insertCategory(UUID orgId, String slug) {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    dsl.insertInto(CATEGORY)
        .set(CATEGORY.ID, id)
        .set(CATEGORY.ORG_ID, orgId)
        .set(CATEGORY.NAME, slug)
        .set(CATEGORY.SLUG, slug)
        .set(CATEGORY.CREATED_AT, now)
        .set(CATEGORY.UPDATED_AT, now)
        .execute();
    return id;
  }

  private UUID insertProduct(UUID orgId, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private UUID insertListing(UUID orgId, UUID productId, String slug, ListingStatus status) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ID, id)
        .set(PRODUCT_LISTING.ORG_ID, orgId)
        .set(PRODUCT_LISTING.PRODUCT_ID, productId)
        .set(PRODUCT_LISTING.TITLE, slug)
        .set(PRODUCT_LISTING.SLUG, slug)
        .set(PRODUCT_LISTING.SALES_PRICE, new BigDecimal("9.99"))
        .set(PRODUCT_LISTING.STATUS, status)
        .set(
            PRODUCT_LISTING.PUBLISHED_AT,
            status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null)
        .execute();
    return id;
  }

  private void setListingStatus(UUID listingId, ListingStatus status) {
    dsl.update(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.STATUS, status)
        .set(
            PRODUCT_LISTING.PUBLISHED_AT,
            status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null)
        .where(PRODUCT_LISTING.ID.eq(listingId))
        .execute();
  }
}
