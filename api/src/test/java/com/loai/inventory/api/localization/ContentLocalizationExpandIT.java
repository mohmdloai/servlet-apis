package com.loai.inventory.api.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Slice L1 (content localization) — EXPAND step. Verifies V63 creates the four {@code
 * *_translation} tables and BACKFILLS them from the legacy single-column / paired {@code
 * _ar}/{@code _en} sources, with the legacy columns left untouched (no reader/writer change).
 *
 * <p>Faithfully exercises the real migration backfill (not a copy): the schema is first migrated to
 * V62 only (translation tables absent, legacy columns present), legacy rows are seeded, then V63 is
 * applied — so its backfill runs against pre-existing data exactly as it would in production.
 */
@Testcontainers
class ContentLocalizationExpandIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;

  // Stable ids so assertions can target the exact seeded rows.
  static final UUID ORG_AR = UUID.randomUUID(); // default_locale = 'ar' (the column default)
  static final UUID ORG_EN = UUID.randomUUID(); // default_locale = 'en'
  static final UUID LISTING_AR = UUID.randomUUID();
  static final UUID LISTING_EN = UUID.randomUUID();
  static final UUID CATEGORY_AR = UUID.randomUUID();
  static final UUID BANNER = UUID.randomUUID(); // ar headline-only + en subheading-only
  static final UUID BANNER_EMPTY = UUID.randomUUID(); // both sides blank -> no rows
  static final UUID PAGE_AR = UUID.randomUUID();

  @BeforeAll
  static void setup() {
    // 1. Schema up to V62 only: translation tables do not exist yet; legacy columns are
    // authoritative.
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("62"))
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

    seedLegacy();

    // 2. Apply V63 -> creates *_translation tables and backfills the seeded legacy data. Pinned to
    // V63: this test validates the EXPAND step against still-present legacy columns, so it must not
    // roll on to V64 (the L6 CONTRACT drop), which would remove the columns it seeds and asserts.
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("63"))
        .load()
        .migrate();
  }

  @AfterAll
  static void teardown() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  static void seedLegacy() {
    dsl.execute("insert into org(id, name, slug) values (?, ?, ?)", ORG_AR, "Org AR", "org-ar");
    dsl.execute(
        "insert into org(id, name, slug, default_locale) values (?, ?, ?, ?)",
        ORG_EN,
        "Org EN",
        "org-en",
        "en");

    UUID prodAr = UUID.randomUUID();
    UUID prodEn = UUID.randomUUID();
    dsl.execute(
        "insert into product(id, org_id, name, base_price, sku) values (?, ?, ?, ?, ?)",
        prodAr,
        ORG_AR,
        "prod-ar",
        new BigDecimal("10.00"),
        "SKU-AR");
    dsl.execute(
        "insert into product(id, org_id, name, base_price, sku) values (?, ?, ?, ?, ?)",
        prodEn,
        ORG_EN,
        "prod-en",
        new BigDecimal("10.00"),
        "SKU-EN");

    // Single-column listings. LISTING_AR carries an Arabic-diacritic title to exercise fold_search.
    dsl.execute(
        "insert into product_listing(id, org_id, product_id, title, marketing_copy, slug, sales_price)"
            + " values (?, ?, ?, ?, ?, ?, ?)",
        LISTING_AR,
        ORG_AR,
        prodAr,
        "أحمد",
        "نسخة",
        "listing-ar",
        new BigDecimal("12.00"));
    dsl.execute(
        "insert into product_listing(id, org_id, product_id, title, marketing_copy, slug, sales_price)"
            + " values (?, ?, ?, ?, ?, ?, ?)",
        LISTING_EN,
        ORG_EN,
        prodEn,
        "Bag",
        null,
        "listing-en",
        new BigDecimal("12.00"));

    dsl.execute(
        "insert into category(id, org_id, name, slug) values (?, ?, ?, ?)",
        CATEGORY_AR,
        ORG_AR,
        "قسم",
        "cat-ar");

    // Paired banner: ar headline-only, en subheading-only (headline_en NULL) — exercises the
    // nullable
    // headline + the (headline OR subheading) non-empty CHECK on the en row.
    dsl.execute(
        "insert into storefront_banner(id, org_id, target_type, target_slug, headline_ar, subheading_en)"
            + " values (?, ?, ?, ?, ?, ?)",
        BANNER,
        ORG_AR,
        "category",
        "cat-ar",
        "هدية",
        "English subheading only");
    // Both sides blank -> backfill must create ZERO rows.
    dsl.execute(
        "insert into storefront_banner(id, org_id, target_type, target_slug) values (?, ?, ?, ?)",
        BANNER_EMPTY,
        ORG_AR,
        "listing",
        "listing-ar");

    dsl.execute(
        "insert into storefront_page(id, org_id, kind, body_ar) values (?, ?, ?, ?)",
        PAGE_AR,
        ORG_AR,
        "about",
        "محتوى الصفحة");
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────
  private static long count(String sql, Object... b) {
    return ((Number) dsl.fetchOne(sql, b).get(0)).longValue();
  }

  private static Object val(String sql, Object... b) {
    Record r = dsl.fetchOne(sql, b);
    return r == null ? null : r.get(0);
  }

  // ── 1. schema present ────────────────────────────────────────────────────────────────────────
  @Test
  void tablesIndexesAndGeneratedColumnsExist() {
    for (String t :
        new String[] {
          "product_listing_translation",
          "category_translation",
          "storefront_banner_translation",
          "storefront_page_translation"
        }) {
      assertEquals(
          1L,
          count(
              "select count(*) from information_schema.tables"
                  + " where table_schema = 'inventorydb' and table_name = ?",
              t),
          t + " table should exist");
    }

    // Generated *_search columns on the two searched tables only.
    assertEquals(
        "ALWAYS",
        val(
            "select is_generated from information_schema.columns where table_schema = 'inventorydb'"
                + " and table_name = 'product_listing_translation' and column_name = 'title_search'"));
    assertEquals(
        "ALWAYS",
        val(
            "select is_generated from information_schema.columns where table_schema = 'inventorydb'"
                + " and table_name = 'category_translation' and column_name = 'name_search'"));

    // Trigram GIN indexes on the searched tables.
    assertEquals(
        1L,
        count(
            "select count(*) from pg_indexes where schemaname = 'inventorydb'"
                + " and indexname = 'product_listing_translation_search_idx'"));
    assertEquals(
        1L,
        count(
            "select count(*) from pg_indexes where schemaname = 'inventorydb'"
                + " and indexname = 'category_translation_search_idx'"));
  }

  // ── 2. backfill parity ───────────────────────────────────────────────────────────────────────
  @Test
  void singleColumnListingsBackfillOneDefaultLocaleRow() {
    assertEquals(
        1L,
        count("select count(*) from product_listing_translation where listing_id = ?", LISTING_AR));
    assertEquals(
        "ar",
        val("select language from product_listing_translation where listing_id = ?", LISTING_AR));
    assertEquals(
        "أحمد",
        val("select title from product_listing_translation where listing_id = ?", LISTING_AR));
    assertEquals(
        "نسخة",
        val(
            "select marketing_copy from product_listing_translation where listing_id = ?",
            LISTING_AR));

    // en-default org: language 'en', null marketing_copy preserved.
    assertEquals(
        "en",
        val("select language from product_listing_translation where listing_id = ?", LISTING_EN));
    assertEquals(
        "Bag",
        val("select title from product_listing_translation where listing_id = ?", LISTING_EN));
    assertNull(
        val(
            "select marketing_copy from product_listing_translation where listing_id = ?",
            LISTING_EN));
  }

  @Test
  void singleColumnCategoryBackfillsOneRow() {
    assertEquals(
        1L, count("select count(*) from category_translation where category_id = ?", CATEGORY_AR));
    assertEquals(
        "ar", val("select language from category_translation where category_id = ?", CATEGORY_AR));
    assertEquals(
        "قسم", val("select name from category_translation where category_id = ?", CATEGORY_AR));
  }

  @Test
  void pairedBannerBackfillsPerNonEmptySideWithNullableHeadline() {
    assertEquals(
        2L,
        count("select count(*) from storefront_banner_translation where banner_id = ?", BANNER));

    // ar row: headline only, subheading null.
    assertEquals(
        "هدية",
        val(
            "select headline from storefront_banner_translation where banner_id = ? and language = 'ar'",
            BANNER));
    assertNull(
        val(
            "select subheading from storefront_banner_translation where banner_id = ? and language = 'ar'",
            BANNER));

    // en row: subheading only, headline null (the nullable-headline case).
    assertNull(
        val(
            "select headline from storefront_banner_translation where banner_id = ? and language = 'en'",
            BANNER));
    assertEquals(
        "English subheading only",
        val(
            "select subheading from storefront_banner_translation where banner_id = ? and language = 'en'",
            BANNER));

    // both-sides-blank banner produced zero rows.
    assertEquals(
        0L,
        count(
            "select count(*) from storefront_banner_translation where banner_id = ?",
            BANNER_EMPTY));
  }

  @Test
  void pairedPageBackfillsOnlyNonEmptySide() {
    assertEquals(
        1L, count("select count(*) from storefront_page_translation where page_id = ?", PAGE_AR));
    assertEquals(
        "ar", val("select language from storefront_page_translation where page_id = ?", PAGE_AR));
    assertEquals(
        "محتوى الصفحة",
        val("select body from storefront_page_translation where page_id = ?", PAGE_AR));
  }

  @Test
  void legacyColumnsLeftUntouched() {
    // The expand step must not mutate the source columns — still authoritative pre-contract.
    assertEquals("أحمد", val("select title from product_listing where id = ?", LISTING_AR));
    assertEquals("قسم", val("select name from category where id = ?", CATEGORY_AR));
    assertEquals("هدية", val("select headline_ar from storefront_banner where id = ?", BANNER));
    assertEquals("محتوى الصفحة", val("select body_ar from storefront_page where id = ?", PAGE_AR));
  }

  // ── 3. generated search populated + Arabic-aware ─────────────────────────────────────────────
  @Test
  void generatedTitleSearchIsFoldedAndArabicAware() {
    // The generated column equals fold_search(title) (alef-hamza أ folded to bare alef ا).
    Object folded = val("select fold_search(?)", "أحمد");
    assertNotNull(folded);
    assertEquals(
        folded,
        val(
            "select title_search from product_listing_translation where listing_id = ?",
            LISTING_AR));

    // A query in the un-hamza'd spelling still matches the hamza'd title via the folded key.
    assertEquals(
        1L,
        count(
            "select count(*) from product_listing_translation"
                + " where title_search like '%' || fold_search(?) || '%'",
            "احمد"));
  }

  // ── 4. idempotency ───────────────────────────────────────────────────────────────────────────
  @Test
  void reRunningGuardedBackfillInsertsNothing() {
    // Re-executing the migration's guarded backfill for listings must affect zero rows.
    int inserted =
        dsl.execute(
            "insert into product_listing_translation (listing_id, language, title, marketing_copy)"
                + " select pl.id, o.default_locale, pl.title, pl.marketing_copy"
                + " from product_listing pl join org o on o.id = pl.org_id"
                + " where not exists (select 1 from product_listing_translation t"
                + "   where t.listing_id = pl.id and t.language = o.default_locale)");
    assertEquals(0, inserted, "guarded backfill should be a no-op on re-run");
  }

  // ── 5. FK cascade ────────────────────────────────────────────────────────────────────────────
  @Test
  void deletingParentCascadesTranslations() {
    UUID prod = UUID.randomUUID();
    UUID listing = UUID.randomUUID();
    dsl.execute(
        "insert into product(id, org_id, name, base_price, sku) values (?, ?, ?, ?, ?)",
        prod,
        ORG_AR,
        "prod-cascade",
        new BigDecimal("1.00"),
        "SKU-CASCADE");
    dsl.execute(
        "insert into product_listing(id, org_id, product_id, title, slug, sales_price)"
            + " values (?, ?, ?, ?, ?, ?)",
        listing,
        ORG_AR,
        prod,
        "Cascade",
        "listing-cascade",
        new BigDecimal("1.00"));
    dsl.execute(
        "insert into product_listing_translation(listing_id, language, title) values (?, 'ar', ?)",
        listing,
        "Cascade");
    assertTrue(
        count("select count(*) from product_listing_translation where listing_id = ?", listing)
            > 0);

    dsl.execute("delete from product_listing where id = ?", listing);
    assertEquals(
        0L,
        count("select count(*) from product_listing_translation where listing_id = ?", listing));
  }
}
