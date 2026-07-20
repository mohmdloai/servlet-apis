package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.service.StorefrontBannerService;
import com.loai.inventory.service.StorefrontBannerService.BannerInput;
import com.loai.inventory.service.StorefrontBannerService.BannerView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * The banner admin write surface (customization epic slice C1): the create/validate matrix
 * (default-locale headline, slug-keyed target resolution, image-key prefix guard, the ≤10-active
 * cap, window integrity), merge-edit, delete, and the atomic reorder set-replace. Drives {@link
 * StorefrontBannerService} directly (presigning is offline). See {@code
 * stories/storefront_banners.md}.
 */
@Testcontainers
class StorefrontBannerAdminIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontBannerService service;
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

  // ───────── AC1: default-locale headline ─────────

  @Test
  void arOnlyCopy_onArDefaultOrg_ok_butMissingDefaultLocaleHeadline_is400() {
    UUID org = insertOrg("acme", "ar");
    insertCategory(org, "kitchen");

    // AR headline on an AR-default org → accepted (EN optional).
    BannerView v = service.create(org, input("مرحبا", null, "category", "kitchen"));
    assertEquals("مرحبا", v.banner().getHeadlineAr());

    // Neither headline → 400.
    assertThrows(
        ValidationException.class,
        () -> service.create(org, input(null, null, "category", "kitchen")));

    // EN-only on an AR-default org (missing the required default-locale copy) → 400.
    assertThrows(
        ValidationException.class,
        () -> service.create(org, input(null, "Hello", "category", "kitchen")));
  }

  @Test
  void enDefaultOrg_requiresEnHeadline() {
    UUID org = insertOrg("acme", "en");
    insertCategory(org, "kitchen");
    assertThrows(
        ValidationException.class,
        () -> service.create(org, input("مرحبا", null, "category", "kitchen")));
    BannerView v = service.create(org, input(null, "Hello", "category", "kitchen"));
    assertEquals("Hello", v.banner().getHeadlineEn());
  }

  // ───────── AC2: target validation ─────────

  @Test
  void targetValidation_ownCategoryAndListing_ok_foreignOrUnknownOrBadType_is400() {
    UUID org = insertOrg("acme", "ar");
    UUID other = insertOrg("rival", "ar");
    insertCategory(org, "kitchen");
    UUID product = insertProduct(org, "SKU1");
    insertListing(org, product, "kettle", ListingStatus.PUBLISHED);
    insertCategory(other, "foreign-cat");

    // own category + own listing → ok
    service.create(org, input("h", null, "category", "kitchen"));
    service.create(org, input("h", null, "listing", "kettle"));

    // another org's category slug → 400 (resolves within THE org only)
    assertThrows(
        ValidationException.class,
        () -> service.create(org, input("h", null, "category", "foreign-cat")));
    // unknown slug → 400
    assertThrows(
        ValidationException.class, () -> service.create(org, input("h", null, "listing", "ghost")));
    // target_type outside the enum → 400
    assertThrows(
        ValidationException.class, () -> service.create(org, input("h", null, "page", "kitchen")));
  }

  @Test
  void draftListingTarget_isStorable_atWriteTime() {
    UUID org = insertOrg("acme", "ar");
    UUID product = insertProduct(org, "SKU1");
    insertListing(org, product, "draft-kettle", ListingStatus.DRAFT);
    // A listing that exists in any status is a valid target at write time (the read filters it).
    BannerView v = service.create(org, input("h", null, "listing", "draft-kettle"));
    assertEquals("draft-kettle", v.banner().getTargetSlug());
  }

  // ───────── AC3: image key prefix guard + presign ─────────

  @Test
  void foreignImageKey_is400_andPresignMintsOrgPrefixedKey() {
    UUID org = insertOrg("acme", "ar");
    insertCategory(org, "kitchen");

    // A key outside {orgId}/banner/ → 400 (cross-tenant guard).
    UUID other = UUID.randomUUID();
    assertThrows(
        ValidationException.class,
        () ->
            service.create(
                org,
                new BannerInput(
                    "h",
                    null,
                    null,
                    null,
                    other + "/banner/x.png",
                    "category",
                    "kitchen",
                    true,
                    null,
                    null)));
    // A logo-prefixed key of the same org is also rejected (wrong prefix).
    assertThrows(
        ValidationException.class,
        () ->
            service.create(
                org,
                new BannerInput(
                    "h",
                    null,
                    null,
                    null,
                    org + "/logo/x.png",
                    "category",
                    "kitchen",
                    true,
                    null,
                    null)));

    StorefrontBannerService.PresignResult presign =
        service.presignImageUpload(org, "hero.png", "image/png");
    assertTrue(presign.objectKey().startsWith(org + "/banner/"), presign.objectKey());
    assertTrue(presign.uploadUrl().startsWith("http"));

    // The minted key attaches cleanly, and a preview URL is presigned.
    BannerView v =
        service.create(
            org,
            new BannerInput(
                "h",
                null,
                null,
                null,
                presign.objectKey(),
                "category",
                "kitchen",
                true,
                null,
                null));
    assertTrue(v.imageUrl() != null && v.imageUrl().startsWith("http"));
  }

  // ───────── AC4: ≤10 active cap ─────────

  @Test
  void eleventhActiveBanner_is400_deactivatingOneUnblocks() {
    UUID org = insertOrg("acme", "ar");
    insertCategory(org, "kitchen");

    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      ids.add(service.create(org, input("h" + i, null, "category", "kitchen")).banner().getId());
    }
    // 11th active → 400
    assertThrows(
        ValidationException.class,
        () -> service.create(org, input("overflow", null, "category", "kitchen")));

    // An inactive banner does not count — creating one at the cap is fine.
    service.create(
        org,
        new BannerInput("draft", null, null, null, null, "category", "kitchen", false, null, null));

    // Deactivate one active → the next activate/create succeeds.
    service.update(
        org,
        ids.get(0),
        new BannerInput(null, null, null, null, null, null, null, false, null, null));
    BannerView ok = service.create(org, input("now-fits", null, "category", "kitchen"));
    assertEquals("now-fits", ok.banner().getHeadlineAr());
  }

  // ───────── AC5: reorder set-replace ─────────

  @Test
  void reorder_setReplacesSortOrder_rejectsForeignOrPartial() {
    UUID org = insertOrg("acme", "ar");
    insertCategory(org, "kitchen");
    UUID a = service.create(org, input("a", null, "category", "kitchen")).banner().getId();
    UUID b = service.create(org, input("b", null, "category", "kitchen")).banner().getId();
    UUID c = service.create(org, input("c", null, "category", "kitchen")).banner().getId();

    List<BannerView> reordered = service.reorder(org, List.of(c, a, b));
    assertEquals(List.of(c, a, b), reordered.stream().map(v -> v.banner().getId()).toList());
    assertEquals(0, reordered.get(0).banner().getSortOrder());
    assertEquals(1, reordered.get(1).banner().getSortOrder());

    // A foreign id → 400.
    assertThrows(
        ValidationException.class, () -> service.reorder(org, List.of(c, a, UUID.randomUUID())));
    // A partial set (missing one) → 400.
    assertThrows(ValidationException.class, () -> service.reorder(org, List.of(c, a)));
    // A duplicate → 400.
    assertThrows(ValidationException.class, () -> service.reorder(org, List.of(a, a, b)));
  }

  // ───────── merge-edit + window + delete ─────────

  @Test
  void update_mergesFields_leavingUnspecifiedUnchanged() {
    UUID org = insertOrg("acme", "ar");
    insertCategory(org, "kitchen");
    BannerView created =
        service.create(
            org,
            new BannerInput(
                "before", null, "sub", null, null, "category", "kitchen", true, null, null));
    UUID id = created.banner().getId();

    // Edit only the AR headline: target + subheading survive.
    BannerView updated =
        service.update(
            org,
            id,
            new BannerInput("after", null, null, null, null, null, null, null, null, null));
    assertEquals("after", updated.banner().getHeadlineAr());
    assertEquals("sub", updated.banner().getSubheadingAr());
    assertEquals("kitchen", updated.banner().getTargetSlug());
  }

  @Test
  void window_startsAfterEnds_is400() {
    UUID org = insertOrg("acme", "ar");
    insertCategory(org, "kitchen");
    OffsetDateTime now = OffsetDateTime.now();
    assertThrows(
        ValidationException.class,
        () ->
            service.create(
                org,
                new BannerInput(
                    "h",
                    null,
                    null,
                    null,
                    null,
                    "category",
                    "kitchen",
                    true,
                    now.plusDays(2),
                    now.plusDays(1))));
  }

  @Test
  void delete_thenReadGone_unknownDelete_is404() {
    UUID org = insertOrg("acme", "ar");
    insertCategory(org, "kitchen");
    UUID id = service.create(org, input("h", null, "category", "kitchen")).banner().getId();
    service.delete(org, id);
    assertTrue(service.list(org).isEmpty());
    assertThrows(NotFoundException.class, () -> service.delete(org, id));
  }

  // ───────── fixtures ─────────

  private static BannerInput input(
      String headlineAr, String headlineEn, String targetType, String targetSlug) {
    return new BannerInput(
        headlineAr, headlineEn, null, null, null, targetType, targetSlug, null, null, null);
  }

  private UUID insertOrg(String slug, String defaultLocale) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .set(ORG.ACTIVE, true)
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
        .set(PRODUCT_LISTING.SLUG, slug)
        .set(PRODUCT_LISTING.SALES_PRICE, new BigDecimal("9.99"))
        .set(PRODUCT_LISTING.STATUS, status)
        .set(
            PRODUCT_LISTING.PUBLISHED_AT,
            status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null)
        .execute();
    return id;
  }
}
