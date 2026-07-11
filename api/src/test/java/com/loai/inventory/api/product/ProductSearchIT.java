package com.loai.inventory.api.product;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.Product;
import com.loai.inventory.repository.ProductRepositoryImpl;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Name/SKU product search — the POS "search to add" picker's backing query ({@code GET
 * /products?q=}). Drives {@link ProductService} directly, same altitude as {@link BarcodeLookupIT}.
 */
@Testcontainers
class ProductSearchIT {

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
  void searchByName_isCaseInsensitiveSubstring() {
    UUID org = seedCatalog();

    List<Product> hits = service.getAll(org, "blue", 0, 20);

    assertEquals(2, hits.size());
    assertTrue(hits.stream().allMatch(p -> p.getName().toLowerCase().contains("blue")));
    assertEquals(2, service.count(org, "blue"));
  }

  @Test
  void searchBySku_matchesTheProductWhoseSkuContainsTheTerm() {
    UUID org = seedCatalog();

    List<Product> hits = service.getAll(org, "mrk", 0, 20);

    assertEquals(1, hits.size());
    assertEquals("Red Marker", hits.get(0).getName());
    assertEquals(1, service.count(org, "mrk"));
  }

  @Test
  void searchIsOrgScoped_doesNotLeakAnotherOrgsMatches() {
    UUID orgA = seedCatalog();
    UUID orgB = createOrg("other");
    service.create(orgB, "Blue Balloon", null, new BigDecimal("2.00"), "BAL-1", null);

    // orgA has two "blue" products; orgB's "Blue Balloon" must not appear in orgA's search.
    assertEquals(2, service.getAll(orgA, "blue", 0, 20).size());
    assertEquals(1, service.getAll(orgB, "blue", 0, 20).size());
  }

  @Test
  void unmatchedTerm_returnsEmpty() {
    UUID org = seedCatalog();

    assertTrue(service.getAll(org, "zzz-nothing", 0, 20).isEmpty());
    assertEquals(0, service.count(org, "zzz-nothing"));
  }

  @Test
  void blankOrNullTerm_returnsTheWholeList() {
    UUID org = seedCatalog();

    assertEquals(3, service.getAll(org, null, 0, 20).size());
    assertEquals(3, service.getAll(org, "   ", 0, 20).size());
    assertEquals(3, service.count(org, null));
  }

  /** Three products in one org: two "Blue …" by name, one matched only by SKU. */
  private UUID seedCatalog() {
    UUID org = createOrg("acme");
    service.create(org, "Blue Pen", "fine tip", new BigDecimal("5.00"), "PEN-1", null);
    service.create(org, "Blue Notebook", null, new BigDecimal("20.00"), "NB-2", null);
    service.create(org, "Red Marker", null, new BigDecimal("8.00"), "MRK-9", null);
    return org;
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
