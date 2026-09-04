package com.loai.inventory.api.product;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.ProductService.CostPriceChange;
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
 * {@code product.cost_price} through {@link ProductService} (stories/product_cost_and_margin.md):
 * the tri-state write (absent → unchanged, null → cleared, value → set and scaled) and the V92
 * CHECK. Same altitude as {@link BarcodeLookupIT}.
 */
@Testcontainers
class ProductCostIT {

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
    service =
        new ProductService(
            new ProductRepositoryImpl(dsl),
            new com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl(),
            dsl);
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
  void create_costPriceAbsentStaysNull_theSixArgFactoryToo() {
    UUID org = createOrg("acme");
    Product viaSix = service.create(org, "Pen", null, new BigDecimal("5.00"), "PEN-1", null);
    Product viaSeven =
        service.create(
            org,
            "Pencil",
            null,
            new BigDecimal("3.00"),
            "PEN-2",
            null,
            CostPriceChange.unchanged());
    assertNull(viaSix.getCostPrice());
    assertNull(viaSeven.getCostPrice());
    assertNull(dsl.fetchOne(PRODUCT, PRODUCT.ID.eq(viaSix.getId())).getCostPrice());
  }

  @Test
  void create_costPriceSetsAndScales() {
    UUID org = createOrg("acme");
    Product p =
        service.create(
            org,
            "Notebook",
            null,
            new BigDecimal("50.00"),
            "NB-1",
            null,
            CostPriceChange.to(new BigDecimal("30")));
    assertEquals(new BigDecimal("30.00"), p.getCostPrice());
    assertEquals(new BigDecimal("30.00"), service.getById(org, p.getId()).getCostPrice());
  }

  @Test
  void create_costPriceNegativeIs400_andNothingIsWritten() {
    UUID org = createOrg("acme");
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                service.create(
                    org,
                    "Notebook",
                    null,
                    new BigDecimal("50.00"),
                    "NB-1",
                    null,
                    CostPriceChange.to(new BigDecimal("-0.01"))));
    assertEquals("cost_price must be >= 0", e.getMessage());
    assertEquals(0, dsl.fetchCount(PRODUCT));
  }

  @Test
  void update_costPriceAbsentLeavesExistingCost() {
    UUID org = createOrg("acme");
    Product p = costed(org, "NB-1", "30.00");
    // The STAFF form's full-replace PUT: every field but the cost, which it never saw.
    Product after =
        service.update(
            org, p.getId(), "Notebook A5", "ruled", new BigDecimal("55.00"), "NB-1", null);
    assertEquals(new BigDecimal("30.00"), after.getCostPrice());
    assertEquals(new BigDecimal("55.00"), after.getBasePrice());
  }

  @Test
  void update_costPriceNullClears() {
    UUID org = createOrg("acme");
    Product p = costed(org, "NB-1", "30.00");
    Product after =
        service.update(
            org,
            p.getId(),
            "Notebook",
            null,
            new BigDecimal("50.00"),
            "NB-1",
            null,
            CostPriceChange.to(null));
    assertNull(after.getCostPrice());
    assertNull(dsl.fetchOne(PRODUCT, PRODUCT.ID.eq(p.getId())).getCostPrice());
  }

  @Test
  void update_costPriceSetsAndScales_zeroIsAValue() {
    UUID org = createOrg("acme");
    Product p = costed(org, "NB-1", "30.00");
    Product at35 =
        service.update(
            org,
            p.getId(),
            "Notebook",
            null,
            new BigDecimal("50.00"),
            "NB-1",
            null,
            CostPriceChange.to(new BigDecimal("35.5")));
    assertEquals(new BigDecimal("35.50"), at35.getCostPrice());
    // 0.00 is "free to me" — a value, distinct from "not costed" (null).
    Product atZero =
        service.update(
            org,
            p.getId(),
            "Notebook",
            null,
            new BigDecimal("50.00"),
            "NB-1",
            null,
            CostPriceChange.to(BigDecimal.ZERO));
    assertEquals(new BigDecimal("0.00"), atZero.getCostPrice());
  }

  private Product costed(UUID org, String sku, String cost) {
    return service.create(
        org,
        "Notebook",
        null,
        new BigDecimal("50.00"),
        sku,
        null,
        CostPriceChange.to(new BigDecimal(cost)));
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
