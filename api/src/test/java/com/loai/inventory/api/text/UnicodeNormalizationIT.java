package com.loai.inventory.api.text;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.service.CustomerService;
import com.loai.inventory.service.ProductService;
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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end coverage for the Unicode-normalization slice ({@code
 * stories/unicode_text_normalization.md}) — the full Java→jOOQ→Postgres path against the real V62
 * migration (fold_search, the generated {@code name_search} columns, ICU-collated name columns,
 * pg_trgm). Pure fold correctness lives in the container-free {@code TextTest}; this proves the
 * wiring: ingress normalization, the derived search key, dedup at the constraint, and sort.
 *
 * <p>Every Arabic string is built from explicit code points ({@link #cp}) so the source is
 * unambiguous ASCII — a literal combining hamza is indistinguishable from a precomposed one on
 * sight.
 */
@Testcontainers
class UnicodeNormalizationIT {

  private static String cp(int... codePoints) {
    return new String(codePoints, 0, codePoints.length);
  }

  // ح م د  — the shared tail of the name.
  private static final String HMD = cp(0x062D, 0x0645, 0x062F);
  private static final String AHMAD_BARE = cp(0x0627) + HMD; // احمد  bare alef (search input)
  private static final String AHMAD_HAMZA = cp(0x0623) + HMD; // أحمد  precomposed alef-with-hamza
  private static final String AHMAD_DECOMPOSED =
      cp(0x0627, 0x0654) + HMD; // alef + combining hamza above → NFC composes to U+0623
  private static final String PHONE_ARABIC_INDIC =
      cp(0x0660, 0x0661, 0x0660, 0x0660, 0x0666, 0x0661, 0x0662, 0x0663, 0x0665, 0x0668, 0x0664);
  private static final String ALEF = cp(0x0627); // ا
  private static final String BAA = cp(0x0628); // ب
  private static final String MEEM = cp(0x0645); // م

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ProductService productService;
  static CustomerService customerService;

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

    productService =
        new ProductService(
            new ProductRepositoryImpl(dsl),
            new com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl(),
            dsl);
    customerService = new CustomerService(dsl, new CustomerRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, "org").set(ORG.SLUG, "org-" + id).execute();
    return id;
  }

  @Test
  void arabicSearchFindsHamzaSpellingVariant() {
    UUID orgId = createOrg();
    Product p =
        productService.create(orgId, AHMAD_HAMZA, null, BigDecimal.ONE, "sku-" + orgId, null);

    // Searching the bare-alef spelling must find the hamza-spelled product via the folded key.
    List<Product> hits = productService.getAll(orgId, AHMAD_BARE, 0, 20);
    assertTrue(
        hits.stream().anyMatch(x -> x.getId().equals(p.getId())),
        "bare-alef search should find the hamza-spelled product");

    // And the DB-generated name_search key is the fully folded form.
    String nameSearch =
        dsl.select(PRODUCT.NAME_SEARCH)
            .from(PRODUCT)
            .where(PRODUCT.ID.eq(p.getId()))
            .fetchOne(0, String.class);
    assertEquals(AHMAD_BARE, nameSearch);
  }

  @Test
  void decomposedNameIsStoredAsPrecomposedNfc() {
    UUID orgId = createOrg();
    // Ingress normalization folds alef+combining-hamza to the precomposed letter at write time.
    Product p =
        productService.create(orgId, AHMAD_DECOMPOSED, null, BigDecimal.ONE, "sku2-" + orgId, null);
    Product loaded = productService.getById(orgId, p.getId());
    assertEquals(AHMAD_HAMZA, loaded.getName(), "decomposed input should store as precomposed NFC");
  }

  @Test
  void arabicIndicDigitsFoldToAsciiViaNumericField() {
    UUID orgId = createOrg();
    // Barcode is a numeric-semantic field: Arabic-Indic digits fold to ASCII at ingress.
    Product p =
        productService.create(
            orgId, "widget", null, BigDecimal.ONE, "sku3-" + orgId, PHONE_ARABIC_INDIC);
    assertEquals("01006123584", productService.getById(orgId, p.getId()).getBarcode());
    // The same product is findable by either digit spelling (proves one stored identity).
    assertEquals(p.getId(), productService.getByBarcode(orgId, PHONE_ARABIC_INDIC).getId());
    assertEquals(p.getId(), productService.getByBarcode(orgId, "01006123584").getId());
  }

  @Test
  void customerEmailDedupsAcrossCase() {
    UUID orgId = createOrg();
    customerService.create(orgId, "Ali@Example.COM");
    // A differently-cased spelling of the same address collides at the (org_id, email) constraint.
    assertThrows(ConflictException.class, () -> customerService.create(orgId, "ali@example.com"));
  }

  @Test
  void nameColumnSortsInIcuCollationOrder() {
    UUID orgId = createOrg();
    // Arabic alphabetical order is alef < baa < meem; ORDER BY name now runs through the
    // ICU-collated
    // column and sorts linguistically rather than erroring or ordering arbitrarily.
    productService.create(orgId, MEEM, null, BigDecimal.ONE, "s-m-" + orgId, null);
    productService.create(orgId, ALEF, null, BigDecimal.ONE, "s-a-" + orgId, null);
    productService.create(orgId, BAA, null, BigDecimal.ONE, "s-b-" + orgId, null);

    List<String> ordered =
        dsl.select(PRODUCT.NAME)
            .from(PRODUCT)
            .where(PRODUCT.ORG_ID.eq(orgId))
            .orderBy(PRODUCT.NAME)
            .fetch(PRODUCT.NAME);
    assertEquals(List.of(ALEF, BAA, MEEM), ordered);
  }

  @Test
  void foldSearchIsIdempotent() {
    // fold_search is IMMUTABLE and idempotent: folding an already-folded value is a fixed point —
    // the
    // property the backfill's re-runnability rests on.
    String once =
        dsl.select(DSL.field("fold_search({0})", String.class, DSL.val(AHMAD_HAMZA)))
            .fetchOne(0, String.class);
    String twice =
        dsl.select(DSL.field("fold_search(fold_search({0}))", String.class, DSL.val(AHMAD_HAMZA)))
            .fetchOne(0, String.class);
    assertEquals(once, twice);
    assertEquals(AHMAD_BARE, once);
  }
}
