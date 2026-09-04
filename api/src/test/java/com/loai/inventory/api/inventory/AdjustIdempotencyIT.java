package com.loai.inventory.api.inventory;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.tables.records.InventoryLogRecord;
import com.loai.inventory.service.InventoryService;
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
 * The adjust action as a stocktake posts it ({@code stories/stocktake_count.md}): a {@code
 * STOCKTAKE} reason on the ledger row, an {@code Idempotency-Key} that applies the delta at most
 * once (V50's column, the {@link RestockIdempotencyIT} rules with the reason added to the
 * fingerprint), and a cause-naming 409 — nothing written, no key claimed — when the delta would
 * take stock below the reserved quantity. Drives {@link InventoryService} directly.
 */
@Testcontainers
class AdjustIdempotencyIT {

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

  // the reason

  @Test
  void stocktakeReason_landsOnTheLedgerRow() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 32, 0);

    Inventory after = service.adjust(org, product, -2, StockReason.STOCKTAKE, actor, null);

    assertEquals(30, after.getStockQty());
    List<InventoryLogRecord> rows = logRows(product);
    assertEquals(1, rows.size());
    assertEquals("STOCKTAKE", rows.get(0).getReason().getLiteral());
    assertEquals(-2, rows.get(0).getStockDelta());
    assertEquals(30, rows.get(0).getStockAfter());
    assertNull(rows.get(0).getOrderId(), "a count is not order-linked");
    assertNull(rows.get(0).getIdempotencyKey(), "no key was sent");
  }

  @Test
  void fourArgOverload_stillWritesAdjustment_regression() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    service.adjust(org, product, 5, actor);

    List<InventoryLogRecord> rows = logRows(product);
    assertEquals("ADJUSTMENT", rows.get(0).getReason().getLiteral());
    assertEquals(15, currentStock(product));
  }

  @Test
  void positiveStocktakeDelta_applies_foundMoreThanTheSystemKnew() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    Inventory after = service.adjust(org, product, 3, StockReason.STOCKTAKE, actor, "k");

    assertEquals(13, after.getStockQty());
    assertEquals("STOCKTAKE", logRows(product).get(0).getReason().getLiteral());
  }

  @Test
  void reasonOutsideThePair_isValidation_nothingWritten() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    assertThrows(
        ValidationException.class,
        () -> service.adjust(org, product, -1, StockReason.RESTOCK, actor, null));
    assertThrows(
        ValidationException.class,
        () -> service.adjust(org, product, -1, StockReason.SOLD, actor, null));
    assertThrows(
        ValidationException.class, () -> service.adjust(org, product, -1, null, actor, null));
    assertEquals(10, currentStock(product));
    assertEquals(0, logRows(product).size());
  }

  // the key

  @Test
  void sameKey_appliesOnce_replayReturnsCurrent() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 32, 0);
    String key = "stocktake:s1:" + product;

    Inventory first = service.adjust(org, product, -2, StockReason.STOCKTAKE, actor, key);
    Inventory replay = service.adjust(org, product, -2, StockReason.STOCKTAKE, actor, key);

    assertEquals(30, first.getStockQty());
    assertEquals(30, replay.getStockQty(), "the replay reflects the applied state, not -4");
    assertEquals(30, currentStock(product));
    List<InventoryLogRecord> rows = logRows(product);
    assertEquals(1, rows.size(), "exactly one ledger row");
    assertEquals(key, rows.get(0).getIdempotencyKey());
  }

  @Test
  void differentKeys_applyIndependently() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    service.adjust(org, product, -1, StockReason.STOCKTAKE, actor, "a");
    Inventory second = service.adjust(org, product, -1, StockReason.STOCKTAKE, actor, "b");

    assertEquals(8, second.getStockQty());
    assertEquals(2, logRows(product).size());
  }

  @Test
  void noKey_appliesEveryCall_regression() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    service.adjust(org, product, -1, StockReason.STOCKTAKE, actor, null);
    service.adjust(org, product, -1, StockReason.STOCKTAKE, actor, "   ");
    Inventory third = service.adjust(org, product, -1, StockReason.STOCKTAKE, actor, "");

    assertEquals(7, third.getStockQty());
    assertEquals(3, logRows(product).size());
  }

  @Test
  void sameKey_differentDelta_isConflict_originalStands() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    service.adjust(org, product, -2, StockReason.STOCKTAKE, actor, "dup");
    assertThrows(
        ConflictException.class,
        () -> service.adjust(org, product, -5, StockReason.STOCKTAKE, actor, "dup"));

    assertEquals(8, currentStock(product));
    assertEquals(1, logRows(product).size());
  }

  @Test
  void sameKey_differentReason_isConflict() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    service.adjust(org, product, -2, StockReason.STOCKTAKE, actor, "dup");
    // Same delta, same product, a different reason — still a client bug, still surfaced.
    assertThrows(
        ConflictException.class,
        () -> service.adjust(org, product, -2, StockReason.ADJUSTMENT, actor, "dup"));
    assertEquals(8, currentStock(product));
  }

  @Test
  void sameKey_differentProduct_isConflict() {
    UUID org = createOrg("acme");
    UUID a = createTrackedProduct(org, 10, 0);
    UUID b = createTrackedProduct(org, 10, 0);

    service.adjust(org, a, -2, StockReason.STOCKTAKE, actor, "k");
    assertThrows(
        ConflictException.class,
        () -> service.adjust(org, b, -2, StockReason.STOCKTAKE, actor, "k"));
    assertEquals(10, currentStock(b));
  }

  @Test
  void sameKeyValue_isScopedPerOrg() {
    UUID orgA = createOrg("a");
    UUID orgB = createOrg("b");
    UUID a = createTrackedProduct(orgA, 10, 0);
    UUID b = createTrackedProduct(orgB, 10, 0);

    service.adjust(orgA, a, -2, StockReason.STOCKTAKE, actor, "shared");
    service.adjust(orgB, b, -2, StockReason.STOCKTAKE, actor, "shared");

    assertEquals(8, currentStock(a));
    assertEquals(8, currentStock(b));
  }

  @Test
  void keyClaimedByRestock_isConflictOnAdjust() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 10, 0);

    service.restock(org, product, 5, actor, "k");
    // One (org, key) namespace across the ledger: the restock claimed it with +5 / RESTOCK.
    assertThrows(
        ConflictException.class,
        () -> service.adjust(org, product, 5, StockReason.STOCKTAKE, actor, "k"));
    assertEquals(15, currentStock(product));
  }

  // the guard

  @Test
  void belowReserved_is409NamingTheHolds_nothingWritten_keyNotClaimed() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 5, 3);
    String key = "stocktake:s1:" + product;

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> service.adjust(org, product, -4, StockReason.STOCKTAKE, actor, key));

    assertTrue(e.getMessage().contains("3 units are held by open orders"), e.getMessage());
    assertTrue(e.getMessage().contains("stock would be 1"), e.getMessage());
    assertEquals(5, currentStock(product), "nothing written");
    assertEquals(0, logRows(product).size(), "no ledger row, so the key was not claimed");

    // The holds are released; the same key now applies once, cleanly.
    dsl.update(INVENTORY)
        .set(INVENTORY.RESERVED_QTY, 0)
        .where(INVENTORY.PRODUCT_ID.eq(product))
        .execute();
    Inventory after = service.adjust(org, product, -4, StockReason.STOCKTAKE, actor, key);
    assertEquals(1, after.getStockQty());
    assertEquals(1, logRows(product).size());
  }

  @Test
  void exactlyToReserved_applies() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 5, 3);

    Inventory after = service.adjust(org, product, -2, StockReason.STOCKTAKE, actor, null);

    assertEquals(3, after.getStockQty());
    assertEquals(3, after.getReservedQty());
    assertEquals(0, after.getAvailableQty());
  }

  @Test
  void belowZero_onUnreservedRow_is409() {
    UUID org = createOrg("acme");
    UUID product = createTrackedProduct(org, 2, 0);

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> service.adjust(org, product, -3, StockReason.ADJUSTMENT, actor, null));

    assertTrue(e.getMessage().contains("stock would be -1"), e.getMessage());
    assertEquals(2, currentStock(product));
  }

  @Test
  void untrackedProduct_is404() {
    UUID org = createOrg("acme");
    UUID product = createProductOnly(org);

    assertThrows(
        NotFoundException.class,
        () -> service.adjust(org, product, 1, StockReason.STOCKTAKE, actor, "k"));
  }

  // fixtures

  private int currentStock(UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.PRODUCT_ID.eq(product))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private List<InventoryLogRecord> logRows(UUID product) {
    return dsl.selectFrom(INVENTORY_LOG)
        .where(INVENTORY_LOG.PRODUCT_ID.eq(product))
        .orderBy(INVENTORY_LOG.ID.asc())
        .fetch();
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

  private UUID createProductOnly(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, "widget")
        .set(PRODUCT.SKU, "SKU-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private UUID createTrackedProduct(UUID org, int stockQty, int reservedQty) {
    UUID id = createProductOnly(org);
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, id)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, reservedQty)
        .execute();
    return id;
  }
}
