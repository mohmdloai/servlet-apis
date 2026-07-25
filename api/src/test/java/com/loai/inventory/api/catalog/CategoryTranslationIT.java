package com.loai.inventory.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.CategoryTranslation;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.CategoryService.CategoryView;
import com.loai.inventory.service.CategoryService.TranslatedNameInput;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductListingService.TranslatedContentInput;
import com.loai.inventory.service.StorefrontService;
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
 * Content-localization slice L3 — category_translation cutover. Drives {@link CategoryService}
 * (admin writes/embed) and {@link StorefrontService} (public nav + listing-chip {@code ?locale=}
 * resolution) directly, the catalog-IT house pattern. Covers: default-locale-required 400,
 * two-language create + nav locale resolution + per-name fallback, unsupported-locale 400, the
 * admin all-languages embed, legacy-column dual-write, PUT replace, listing-chip locale
 * consistency, and the folded Arabic {@code name_search} (proven at the DB — categories have no
 * HTTP search surface).
 */
@Testcontainers
class CategoryTranslationIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectStorage storage;
  static CategoryService admin;
  static ProductListingService listingAdmin;
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
        new CategoryService(
            dsl, new CategoryRepositoryFactoryImpl(), new OrgRepositoryFactoryImpl());
    listingAdmin =
        new ProductListingService(
            dsl,
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl(),
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
        "TRUNCATE category_translation, product_listing_translation, product_listing_category,"
            + " product_listing, category, product, org RESTART IDENTITY CASCADE");
  }

  // ── AC1: default-locale required ──────────────────────────────────────────────────────────────
  @Test
  void createWithoutDefaultLocaleName_is400() {
    UUID orgId = seed("shop", "ar");
    // ar-default org, but only an en translation → the required ar row is missing.
    assertThrows(
        ValidationException.class,
        () -> admin.create(orgId, "en-only", null, content(tr("en", "Books"))));
  }

  // ── AC2: two-language create + nav locale resolution + per-name fallback ──────────────────────
  @Test
  void twoLanguageCreate_resolvesNavByLocale_withFallback() {
    UUID orgId = seed("shop", "ar");
    admin.create(orgId, "books", null, content(tr("ar", "كتب"), tr("en", "Books")));
    admin.create(orgId, "only-ar", null, content(tr("ar", "قلم"))); // no en row

    // en nav: English name for the bilingual node; the ar-only node falls back to its ar name.
    List<StorefrontService.CategoryNav> en = storefront.listCategories("shop", "en");
    assertEquals("Books", nameOf(en, "books"));
    assertEquals("قلم", nameOf(en, "only-ar")); // fallback, never null

    // ar nav: both verbatim.
    List<StorefrontService.CategoryNav> ar = storefront.listCategories("shop", "ar");
    assertEquals("كتب", nameOf(ar, "books"));

    // unset locale → org default (ar).
    assertEquals("كتب", nameOf(storefront.listCategories("shop", null), "books"));
  }

  // ── AC (nav consistency): unsupported locale → 400 ────────────────────────────────────────────
  @Test
  void unsupportedLocale_is400() {
    UUID orgId = seed("shop", "ar");
    admin.create(orgId, "books", null, content(tr("ar", "كتب")));
    assertThrows(ValidationException.class, () -> storefront.listCategories("shop", "fr"));
  }

  // ── AC3: listing-row category chips resolve to the same locale as the listing ─────────────────
  @Test
  void listingChipsResolveToLocale() {
    UUID orgId = seed("shop", "ar");
    Category cat =
        admin.create(orgId, "kitchen", null, content(tr("ar", "مطبخ"), tr("en", "Kitchen")));
    UUID productId = seedProduct(orgId, "SKU-1");
    UUID listingId =
        listingAdmin
            .create(
                orgId,
                productId,
                "kettle",
                new BigDecimal("50.00"),
                listingContent("ar", "غلاية", "en", "Kettle"))
            .getId();
    listingAdmin.setCategories(orgId, listingId, Set.of(cat.getId()));
    listingAdmin.publish(orgId, listingId);

    StorefrontService.ListingView en = storefront.getListing("shop", "kettle", "en");
    assertEquals("Kettle", en.title());
    assertEquals(1, en.categories().size());
    assertEquals("Kitchen", en.categories().get(0).name());

    StorefrontService.ListingView ar = storefront.getListing("shop", "kettle", "ar");
    assertEquals("مطبخ", ar.categories().get(0).name());
  }

  // ── AC4: admin embed + legacy dual-write ──────────────────────────────────────────────────────
  @Test
  void adminEmbedsAllLanguages_andDualWritesLegacyDefault() {
    UUID orgId = seed("shop", "ar");
    UUID id =
        admin.create(orgId, "mugs", null, content(tr("ar", "أكواب"), tr("en", "Mugs"))).getId();

    CategoryView view = admin.getById(orgId, id);
    List<CategoryTranslation> t = view.translations();
    assertEquals(2, t.size());
    assertTrue(t.stream().anyMatch(x -> x.language().equals("ar") && x.name().equals("أكواب")));
    assertTrue(t.stream().anyMatch(x -> x.language().equals("en") && x.name().equals("Mugs")));
    // (The legacy category.name dual-write assertion was removed at L6 — the column is dropped and
    // category_translation is the single source of truth.)
  }

  @Test
  void putReplacesTranslationSet() {
    UUID orgId = seed("shop", "ar");
    UUID id = admin.create(orgId, "swap", null, content(tr("ar", "قديم"), tr("en", "Old"))).getId();
    // Update with only ar → the en row must be dropped (PUT replaces the set).
    admin.update(orgId, id, "swap", null, content(tr("ar", "جديد")));
    List<CategoryTranslation> t = admin.getById(orgId, id).translations();
    assertEquals(1, t.size());
    assertEquals("جديد", t.get(0).name());
  }

  // ── AC5: folded Arabic name_search (generated column; no HTTP search surface for categories) ──
  @Test
  void arabicNameSearch_foldsHamza() {
    UUID orgId = seed("shop", "ar");
    UUID id = admin.create(orgId, "ahmed", null, content(tr("ar", "أحمد"))).getId();

    // A query without the hamza still matches the hamza'd name via the generated name_search.
    Integer hit =
        dsl.selectCount()
            .from("category_translation")
            .where(
                "category_id = ? and language = 'ar'"
                    + " and name_search like '%' || fold_search(?) || '%'",
                id, "احمد")
            .fetchOne(0, Integer.class);
    assertEquals(1, hit);
  }

  // ── helpers ───────────────────────────────────────────────────────────────────────────────────
  private static UUID seed(String slug, String defaultLocale) {
    UUID orgId = UUID.randomUUID();
    dsl.execute(
        "insert into org(id, name, slug, default_locale) values (?, ?, ?, ?)",
        orgId,
        slug,
        slug,
        defaultLocale);
    return orgId;
  }

  private static UUID seedProduct(UUID orgId, String sku) {
    UUID productId = UUID.randomUUID();
    dsl.execute(
        "insert into product(id, org_id, name, base_price, sku) values (?, ?, ?, ?, ?)",
        productId,
        orgId,
        "p",
        new BigDecimal("1.00"),
        sku);
    return productId;
  }

  private static TranslatedNameInput content(CategoryTranslation... translations) {
    return new TranslatedNameInput(List.of(translations), null);
  }

  private static CategoryTranslation tr(String lang, String name) {
    return new CategoryTranslation(lang, name);
  }

  private static TranslatedContentInput listingContent(String l1, String t1, String l2, String t2) {
    return new TranslatedContentInput(
        List.of(
            new com.loai.inventory.domain.model.ProductListingTranslation(l1, t1, null),
            new com.loai.inventory.domain.model.ProductListingTranslation(l2, t2, null)),
        null,
        null);
  }

  private static String nameOf(List<StorefrontService.CategoryNav> nav, String slug) {
    return nav.stream().filter(n -> n.slug().equals(slug)).findFirst().orElseThrow().name();
  }
}
