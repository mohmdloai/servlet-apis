package com.loai.inventory.api.localization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Slice L6 (content localization) — CONTRACT step. After the full migration set (through V64), the
 * legacy/paired localized columns are gone and the {@code *_translation} tables are the single
 * source of truth. This is a pure schema assertion: the migration ran, the columns dropped, and
 * nothing L1 built (the translation tables, their generated {@code *_search} columns + GIN indexes,
 * and {@code product.name_search} — product stays single-column) was collateral damage.
 *
 * <p>The behavioural half of AC — that public + admin reads for all four entities resolve
 * identically to their post-L2–L4 shape now that the redundant source is gone — is covered by the
 * existing translation ITs ({@code ProductListingTranslationIT}, {@code CategoryTranslationIT},
 * {@code PublicBannersIT}, {@code StorefrontPagesIT}, {@code OrderLineTitleSnapshotIT}), which pass
 * unchanged against the dropped schema. Full-suite green is the real acceptance (AC2).
 */
@Testcontainers
class ContentLocalizationContractIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;

  @BeforeAll
  static void setup() {
    // Full migration set (through V64 — the contract drop). No target: the whole history applies.
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
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  @AfterAll
  static void teardown() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  // --- AC1: the dropped columns are gone ---

  @Test
  void productListing_legacyTextColumns_areDropped() {
    assertFalse(columnExists("product_listing", "title"), "product_listing.title must be dropped");
    assertFalse(
        columnExists("product_listing", "marketing_copy"),
        "product_listing.marketing_copy must be dropped");
  }

  @Test
  void category_legacyNameColumn_isDropped() {
    assertFalse(columnExists("category", "name"), "category.name must be dropped");
  }

  @Test
  void storefrontBanner_pairedColumns_areDropped() {
    for (String col :
        new String[] {"headline_ar", "headline_en", "subheading_ar", "subheading_en"}) {
      assertFalse(
          columnExists("storefront_banner", col), "storefront_banner." + col + " must be dropped");
    }
  }

  @Test
  void storefrontPage_pairedColumns_areDropped() {
    assertFalse(
        columnExists("storefront_page", "body_ar"), "storefront_page.body_ar must be dropped");
    assertFalse(
        columnExists("storefront_page", "body_en"), "storefront_page.body_en must be dropped");
  }

  // --- AC1: the translation tables + their search infrastructure are untouched ---

  @Test
  void translationTables_andGeneratedSearchColumns_survive() {
    assertTrue(columnExists("product_listing_translation", "title"));
    assertTrue(
        columnExists("product_listing_translation", "title_search"),
        "the generated title_search fold column must survive");
    assertTrue(columnExists("category_translation", "name"));
    assertTrue(
        columnExists("category_translation", "name_search"),
        "the generated name_search fold column must survive");
    assertTrue(columnExists("storefront_banner_translation", "headline"));
    assertTrue(columnExists("storefront_banner_translation", "subheading"));
    assertTrue(columnExists("storefront_page_translation", "body"));
  }

  @Test
  void translationSearchIndexes_survive() {
    assertTrue(
        indexExists("product_listing_translation_search_idx"),
        "the listing title trigram GIN must survive");
    assertTrue(
        indexExists("category_translation_search_idx"),
        "the category name trigram GIN must survive");
  }

  // --- AC1: product stays single-column — its V62 search is still live ---

  @Test
  void productNameSearch_andItsIndex_survive() {
    assertTrue(
        columnExists("product", "name_search"),
        "product.name_search must survive (product is out of scope, stays single-column)");
    assertTrue(indexExists("product_name_search_trgm_idx"), "product.name_search GIN must survive");
  }

  // --- helpers ---

  private static boolean columnExists(String table, String column) {
    return dsl.fetchExists(
        DSL.selectOne()
            .from(DSL.table("information_schema.columns"))
            .where(DSL.field("table_schema").eq("inventorydb"))
            .and(DSL.field("table_name").eq(table))
            .and(DSL.field("column_name").eq(column)));
  }

  private static boolean indexExists(String indexName) {
    return dsl.fetchExists(
        DSL.selectOne()
            .from(DSL.table("pg_indexes"))
            .where(DSL.field("schemaname").eq("inventorydb"))
            .and(DSL.field("indexname").eq(indexName)));
  }
}
