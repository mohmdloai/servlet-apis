package com.loai.inventory.api.inventory;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.service.InventoryService;
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
 * Idempotent restock — a caller-supplied {@code Idempotency-Key} applies the {@code +stock} at most
 * once (backend story {@code product_barcode_lookup.md} §Idempotent restock; the seam the deferred
 * offline stock-take queue replays against). Drives {@link InventoryService} directly.
 */
@Testcontainers
class RestockIdempotencyIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static InventoryService service;

  private final ActorContext actor = ActorContext.user(UUID.randomUUID().toString());

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
        new InventoryService(
            dsl,
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new ProductRepositoryImpl(dsl),
            new SalesOrderRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE inventory_log, inventory, product, org RESTART IDENTITY CASCADE");
  }

  @Test
  void sameKey_appliesOnce_replayIsNoOp() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 0);
    String key = "offline-op-1";

    Inventory first = service.restock(org, product, 10, actor, key);
    Inventory replay = service.restock(org, product, 10, actor, key);

    assertEquals(10, first.getStockQty());
    // Replay did NOT increment again — stock stays 10 and the response reflects the applied state.
    assertEquals(10, replay.getStockQty());
    assertEquals(10, currentStock(product));
    // Exactly one +stock ledger row was written.
    assertEquals(1, logRowCount(product));
  }

  @Test
  void differentKeys_applyIndependently() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 0);

    service.restock(org, product, 10, actor, "op-A");
    Inventory second = service.restock(org, product, 10, actor, "op-B");

    assertEquals(20, second.getStockQty());
    assertEquals(20, currentStock(product));
    assertEquals(2, logRowCount(product));
  }

  @Test
  void noKey_appliesEveryCall_regression() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 0);

    service.restock(org, product, 10, actor);
    Inventory second = service.restock(org, product, 10, actor);

    // Absent key = today's behaviour: every call applies.
    assertEquals(20, second.getStockQty());
    assertEquals(2, logRowCount(product));
  }

  @Test
  void blankKey_isTreatedAsAbsent() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 0);

    service.restock(org, product, 10, actor, "   ");
    Inventory second = service.restock(org, product, 10, actor, "");

    assertEquals(20, second.getStockQty());
    assertEquals(2, logRowCount(product));
  }

  @Test
  void sameKey_differentQty_isConflict_notSilentlySwallowed() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 0);

    service.restock(org, product, 10, actor, "dup-key");
    // Reusing the key with a different quantity is a client bug — surfaced as 409, not ignored.
    assertThrows(
        ConflictException.class, () -> service.restock(org, product, 50, actor, "dup-key"));
    // The original +10 stands; the mismatched replay applied nothing.
    assertEquals(10, currentStock(product));
    assertEquals(1, logRowCount(product));
  }

  @Test
  void sameKey_differentProduct_isConflict() {
    UUID org = createOrg("acme");
    UUID productA = createTrackedProduct(org, 0);
    UUID productB = createTrackedProduct(org, 0);

    service.restock(org, productA, 10, actor, "k");
    assertThrows(ConflictException.class, () -> service.restock(org, productB, 10, actor, "k"));
  }

  @Test
  void sameKeyValue_isScopedPerOrg() {
    UUID orgA = createOrg("a");
    UUID orgB = createOrg("b");
    UUID productA = createTrackedProduct(orgA, 0);
    UUID productB = createTrackedProduct(orgB, 0);

    // The same key string in two different orgs are independent claims (UNIQUE is (org_id, key)).
    Inventory a = service.restock(orgA, productA, 10, actor, "shared-key");
    Inventory b = service.restock(orgB, productB, 10, actor, "shared-key");

    assertEquals(10, a.getStockQty());
    assertEquals(10, b.getStockQty());
  }

  private int currentStock(UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.PRODUCT_ID.eq(product))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private int logRowCount(UUID product) {
    return dsl.fetchCount(
        dsl.selectFrom(INVENTORY_LOG).where(INVENTORY_LOG.PRODUCT_ID.eq(product)));
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

  private UUID createTrackedProduct(UUID org, int stockQty) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, "widget")
        .set(PRODUCT.SKU, "SKU-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, id)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
    return id;
  }
}
