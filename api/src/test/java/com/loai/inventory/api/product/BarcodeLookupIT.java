package com.loai.inventory.api.product;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.service.ProductService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * Barcode resolve + write-through — the scanner's lookup seam ({@code FLOW.md §3} step 1, backend
 * story {@code product_barcode_lookup.md}). Drives {@link ProductService} directly (same altitude
 * as {@link ProductDeleteIT}).
 */
@Testcontainers
class BarcodeLookupIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ProductService service;

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
    service = new ProductService(new ProductRepositoryImpl(dsl), dsl);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE product_listing, product, org RESTART IDENTITY CASCADE");
  }

  @Test
  void createWithBarcode_thenResolveByBarcode_returnsIdentityAndPrice() {
    UUID org = createOrg("acme");
    Product created =
        service.create(
            org, "Blue Pen", "fine tip", new BigDecimal("5.00"), "PEN-1", "8901234567890");

    Product resolved = service.getByBarcode(org, "8901234567890");

    assertEquals(created.getId(), resolved.getId());
    assertEquals("Blue Pen", resolved.getName());
    assertEquals("PEN-1", resolved.getSku());
    assertEquals("8901234567890", resolved.getBarcode());
    assertEquals(0, new BigDecimal("5.00").compareTo(resolved.getBasePrice()));
  }

  @Test
  void duplicateBarcodeInSameOrg_isConflict() {
    UUID org = createOrg("acme");
    service.create(org, "Pen", null, new BigDecimal("5.00"), "PEN-1", "DUP-1");

    assertThrows(
        ConflictException.class,
        () -> service.create(org, "Pencil", null, new BigDecimal("3.00"), "PEN-2", "DUP-1"));
  }

  @Test
  void sameBarcodeAcrossDifferentOrgs_isAllowed() {
    UUID orgA = createOrg("a");
    UUID orgB = createOrg("b");
    service.create(orgA, "Pen", null, new BigDecimal("5.00"), "PEN-1", "SHARED");
    // The unique index is (org_id, barcode) — the same code in another org is a different product.
    Product b = service.create(orgB, "Pen", null, new BigDecimal("5.00"), "PEN-1", "SHARED");

    assertEquals(b.getId(), service.getByBarcode(orgB, "SHARED").getId());
  }

  @Test
  void unknownBarcode_isNotFound() {
    UUID org = createOrg("acme");
    assertThrows(NotFoundException.class, () -> service.getByBarcode(org, "NO-SUCH-CODE"));
  }

  @Test
  void blankBarcodeLookup_isValidationError() {
    UUID org = createOrg("acme");
    assertThrows(ValidationException.class, () -> service.getByBarcode(org, "   "));
  }

  @Test
  void blankBarcodeOnCreate_storesNull_andManyNullsCoexist() {
    UUID org = createOrg("acme");
    Product a = service.create(org, "A", null, new BigDecimal("1.00"), "A", "  ");
    Product b = service.create(org, "B", null, new BigDecimal("1.00"), "B", null);

    // Blank/absent barcodes normalize to NULL; the partial unique index (WHERE barcode IS NOT NULL)
    // lets many NULLs coexist — no false conflict.
    assertNull(a.getBarcode());
    assertNull(b.getBarcode());
  }

  @Test
  void updateSetsBarcode_thenClearingItFreesTheCodeForReuse() {
    UUID org = createOrg("acme");
    Product p = service.create(org, "Pen", null, new BigDecimal("5.00"), "PEN-1", "CODE-1");

    // Clear the barcode.
    service.update(org, p.getId(), "Pen", null, new BigDecimal("5.00"), "PEN-1", null);
    assertThrows(NotFoundException.class, () -> service.getByBarcode(org, "CODE-1"));

    // The freed code can now be assigned to another product without a conflict.
    Product other = service.create(org, "Pencil", null, new BigDecimal("3.00"), "PEN-2", "CODE-1");
    assertEquals(other.getId(), service.getByBarcode(org, "CODE-1").getId());
  }

  @Test
  void updateToABarcodeOwnedByAnotherProduct_isConflict() {
    UUID org = createOrg("acme");
    service.create(org, "Pen", null, new BigDecimal("5.00"), "PEN-1", "OWNED");
    Product other = service.create(org, "Pencil", null, new BigDecimal("3.00"), "PEN-2", "FREE");

    assertThrows(
        ConflictException.class,
        () ->
            service.update(
                org, other.getId(), "Pencil", null, new BigDecimal("3.00"), "PEN-2", "OWNED"));
  }

  @Test
  void tooLongBarcode_isValidationError() {
    UUID org = createOrg("acme");
    String tooLong = "1".repeat(65);
    assertThrows(
        ValidationException.class,
        () -> service.create(org, "X", null, new BigDecimal("1.00"), "X", tooLong));
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
}
