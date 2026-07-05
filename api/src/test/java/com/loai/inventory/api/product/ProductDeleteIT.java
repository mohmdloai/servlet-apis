package com.loai.inventory.api.product;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.generated.enums.ListingStatus;
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
 * Product deletion: an unreferenced product deletes; one still referenced (e.g. by a {@code
 * product_listing}) surfaces the FK violation as a clean 409 {@link ConflictException} rather than
 * a raw jOOQ exception bubbling to a 500.
 */
@Testcontainers
class ProductDeleteIT {

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
  void deletesUnreferencedProduct() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");

    service.delete(org, product);

    assertEquals(0, dsl.fetchCount(dsl.selectFrom(PRODUCT).where(PRODUCT.ID.eq(product))));
  }

  @Test
  void referencedProduct_isConflictNot500() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    // A listing references the product via FK — deletion must be blocked with a 409.
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ORG_ID, org)
        .set(PRODUCT_LISTING.PRODUCT_ID, product)
        .set(PRODUCT_LISTING.TITLE, "Widget")
        .set(PRODUCT_LISTING.SLUG, "widget")
        .set(PRODUCT_LISTING.SALES_PRICE, new BigDecimal("9.99"))
        .set(PRODUCT_LISTING.STATUS, ListingStatus.DRAFT)
        .execute();

    assertThrows(ConflictException.class, () -> service.delete(org, product));
    // The product is still there (the delete rolled back, not a partial state).
    assertEquals(1, dsl.fetchCount(dsl.selectFrom(PRODUCT).where(PRODUCT.ID.eq(product))));
  }

  @Test
  void productReferencedByOrderLine_isConflictNot500() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    // An order line references the product via a hard FK (product_id NOT NULL REFERENCES
    // product(id), no ON DELETE) — deleting the product must be blocked with a 409, not a 500.
    UUID soId = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO sales_order (id, org_id, order_number, channel, status)"
            + " VALUES (?, ?, ?, 'ONLINE'::order_channel, 'PENDING_PAYMENT'::order_status)",
        soId,
        org,
        "SO-1");
    dsl.execute(
        "INSERT INTO sales_order_line"
            + " (id, sales_order_id, product_id, description, quantity, unit_price,"
            + " line_subtotal, line_total)"
            + " VALUES (?, ?, ?, 'Widget', 1, 10.00, 10.00, 10.00)",
        UUID.randomUUID(),
        soId,
        product);

    assertThrows(ConflictException.class, () -> service.delete(org, product));
    assertEquals(1, dsl.fetchCount(dsl.selectFrom(PRODUCT).where(PRODUCT.ID.eq(product))));
  }

  @Test
  void missingProduct_isNotFound() {
    UUID org = createOrg("acme");
    assertThrows(NotFoundException.class, () -> service.delete(org, UUID.randomUUID()));
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
