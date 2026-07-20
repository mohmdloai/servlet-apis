package com.loai.inventory.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.ProductListingTranslation;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductListingService.ListingView;
import com.loai.inventory.service.ProductListingService.TranslatedContentInput;
import com.loai.inventory.service.StorefrontService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * Content-localization slice L2 — product_listing translation cutover. Drives {@link
 * ProductListingService} (admin writes/embed) and {@link StorefrontService} (public {@code
 * ?locale=} resolution + per-language Arabic search) directly, the catalog-IT house pattern.
 * Covers: default-locale-required 400, two-language create + locale-resolved read + per-field
 * fallback, unsupported-locale 400, Arabic folded search within a locale, the admin all-languages
 * embed, and the legacy-column dual-write (pre-L6 rollback safety).
 */
@Testcontainers
class ProductListingTranslationIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectStorage storage;
  static ProductListingService admin;
  static StorefrontService storefront;

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

    admin =
        new ProductListingService(
            dsl,
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            storage);
    storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new StorefrontBannerRepositoryFactoryImpl(),
            new ListingReviewRepositoryFactoryImpl(),
            storage,
            null,
            null);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) {
      storage.close();
    }
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE product_listing_translation, product_listing_image, product_listing_category,"
            + " product_listing, category, product, org RESTART IDENTITY CASCADE");
  }

  // ── AC1: default-locale required ──────────────────────────────────────────────────────────────
  @Test
  void createWithoutDefaultLocaleTitle_is400() {
    Seed s = seed("shop", "ar");
    // ar-default org, but only an en translation → the required ar row is missing.
    assertThrows(
        ValidationException.class,
        () ->
            admin.create(
                s.orgId,
                s.productId,
                "no-ar",
                new BigDecimal("10.00"),
                content(tr("en", "English only", null))));
  }

  // ── AC2: two-language create + locale-resolved read + per-field fallback ──────────────────────
  @Test
  void twoLanguageCreate_resolvesByLocale_withFieldFallback() {
    Seed s = seed("shop", "ar");
    UUID id =
        admin
            .create(
                s.orgId,
                s.productId,
                "kettle",
                new BigDecimal("50.00"),
                content(
                    tr("ar", "غلاية", "وصف عربي"),
                    tr("en", "Kettle", null))) // en marketing_copy intentionally absent
            .getId();
    admin.publish(s.orgId, id);

    // en request: English title, but marketing_copy falls back to the ar value (field-level).
    StorefrontService.ListingView en = storefront.getListing("shop", "kettle", "en");
    assertEquals("Kettle", en.title());
    assertEquals("وصف عربي", en.marketingCopy());

    // ar request: the ar row verbatim.
    StorefrontService.ListingView ar = storefront.getListing("shop", "kettle", "ar");
    assertEquals("غلاية", ar.title());
    assertEquals("وصف عربي", ar.marketingCopy());

    // unset locale → org default (ar).
    assertEquals("غلاية", storefront.getListing("shop", "kettle", null).title());

    // list read resolves too.
    StorefrontService.ListingView listEn =
        storefront
            .listPublished("shop", null, null, null, null, null, null, "en", 0, 20)
            .items()
            .get(0);
    assertEquals("Kettle", listEn.title());
  }

  @Test
  void missingLocaleRow_fallsBackToDefaultTitle() {
    Seed s = seed("shop", "ar");
    UUID id =
        admin
            .create(
                s.orgId,
                s.productId,
                "only-ar",
                new BigDecimal("5.00"),
                content(tr("ar", "قلم", null)))
            .getId();
    admin.publish(s.orgId, id);

    // No en row at all → en request falls back to the ar (default) title, never null.
    assertEquals("قلم", storefront.getListing("shop", "only-ar", "en").title());
  }

  // ── AC3: unsupported locale → 400 ─────────────────────────────────────────────────────────────
  @Test
  void unsupportedLocale_is400() {
    Seed s = seed("shop", "ar");
    UUID id =
        admin
            .create(s.orgId, s.productId, "x", new BigDecimal("1.00"), content(tr("ar", "س", null)))
            .getId();
    admin.publish(s.orgId, id);
    assertThrows(ValidationException.class, () -> storefront.getListing("shop", "x", "fr"));
    assertThrows(
        ValidationException.class,
        () -> storefront.listPublished("shop", null, null, null, null, null, null, "fr", 0, 20));
  }

  // ── AC4: Arabic folded per-language search ────────────────────────────────────────────────────
  @Test
  void arabicSearch_foldsHamza_withinLocale() {
    Seed s = seed("shop", "ar");
    UUID id =
        admin
            .create(
                s.orgId,
                s.productId,
                "ahmed",
                new BigDecimal("9.00"),
                content(tr("ar", "أحمد", null)))
            .getId();
    admin.publish(s.orgId, id);

    // Query without the hamza still matches the hamza'd title via title_search (fold_search).
    var hit = storefront.listPublished("shop", null, "احمد", null, null, null, null, "ar", 0, 20);
    assertEquals(1, hit.total());
    assertEquals("أحمد", hit.items().get(0).title());

    // A term matching neither locale returns nothing.
    assertEquals(
        0,
        storefront.listPublished("shop", null, "zzz", null, null, null, null, "ar", 0, 20).total());
  }

  // ── AC5 + AC6: admin embed + legacy dual-write ────────────────────────────────────────────────
  @Test
  void adminEmbedsAllLanguages_andDualWritesLegacyDefault() {
    Seed s = seed("shop", "ar");
    UUID id =
        admin
            .create(
                s.orgId,
                s.productId,
                "mug",
                new BigDecimal("12.00"),
                content(tr("ar", "كوب", "وصف"), tr("en", "Mug", "desc")))
            .getId();

    ListingView view = admin.getById(s.orgId, id);
    List<ProductListingTranslation> t = view.translations();
    assertEquals(2, t.size());
    assertTrue(t.stream().anyMatch(x -> x.language().equals("ar") && x.title().equals("كوب")));
    assertTrue(t.stream().anyMatch(x -> x.language().equals("en") && x.title().equals("Mug")));
    // (The legacy product_listing.title/marketing_copy dual-write assertion was removed at L6 — the
    // columns are dropped and product_listing_translation is the single source of truth.)
  }

  @Test
  void putReplacesTranslationSet() {
    Seed s = seed("shop", "ar");
    UUID id =
        admin
            .create(
                s.orgId,
                s.productId,
                "swap",
                new BigDecimal("3.00"),
                content(tr("ar", "قديم", null), tr("en", "Old", null)))
            .getId();
    // Update with only ar → the en row must be dropped (PUT replaces the set).
    admin.update(s.orgId, id, "swap", new BigDecimal("3.00"), content(tr("ar", "جديد", null)));
    List<ProductListingTranslation> t = admin.getById(s.orgId, id).translations();
    assertEquals(1, t.size());
    assertEquals("جديد", t.get(0).title());
  }

  // ── helpers ───────────────────────────────────────────────────────────────────────────────────
  private record Seed(UUID orgId, UUID productId) {}

  private static Seed seed(String slug, String defaultLocale) {
    UUID orgId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    dsl.execute(
        "insert into org(id, name, slug, default_locale) values (?, ?, ?, ?)",
        orgId,
        slug,
        slug,
        defaultLocale);
    dsl.execute(
        "insert into product(id, org_id, name, base_price, sku) values (?, ?, ?, ?, ?)",
        productId,
        orgId,
        "p",
        new BigDecimal("1.00"),
        "SKU-" + slug);
    return new Seed(orgId, productId);
  }

  private static TranslatedContentInput content(ProductListingTranslation... translations) {
    return new TranslatedContentInput(List.of(translations), null, null);
  }

  private static ProductListingTranslation tr(String lang, String title, String copy) {
    return new ProductListingTranslation(lang, title, copy);
  }
}
