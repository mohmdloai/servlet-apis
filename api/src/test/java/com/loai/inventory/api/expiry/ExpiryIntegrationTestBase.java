package com.loai.inventory.api.expiry;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.OrderExpiryService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared harness for the expire-pending-orders slice. Boots one PostgreSQL container, runs all
 * Flyway migrations (including V29), and wires {@link OrderExpiryService} against the real jOOQ
 * repository factories — no Tomcat/Redis/JWT, so each scenario is fast and deterministic.
 *
 * <p>The load-bearing ground-truth invariant — {@code SUM(active reservations) == reserved_qty} per
 * {@code (org_id, product_id)} — is asserted as an {@link AfterEach} on <b>every</b> test, exactly
 * once here, so a new scenario cannot forget it (see {@code stories/expire_pending_orders.md}
 * "Ground truth").
 */
@Testcontainers
abstract class ExpiryIntegrationTestBase {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  protected static DSLContext dsl;
  protected static OrderExpiryService expiryService;

  protected final SalesOrderRepositoryFactoryImpl salesOrderRepoFactory =
      new SalesOrderRepositoryFactoryImpl();
  protected final InventoryRepositoryFactoryImpl inventoryRepoFactory =
      new InventoryRepositoryFactoryImpl();
  protected final InventoryReservationRepositoryFactoryImpl reservationRepoFactory =
      new InventoryReservationRepositoryFactoryImpl();
  protected final InventoryLogRepositoryFactoryImpl inventoryLogRepoFactory =
      new InventoryLogRepositoryFactoryImpl();

  private final AtomicInteger orderSeq = new AtomicInteger(1);

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
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchemaAndService() {
    dsl.execute(
        "TRUNCATE inventory_reservation, inventory_log, sales_order_line, sales_order, inventory,"
            + " product, org RESTART IDENTITY CASCADE");
    expiryService =
        new OrderExpiryService(
            dsl,
            salesOrderRepoFactory,
            inventoryRepoFactory,
            reservationRepoFactory,
            inventoryLogRepoFactory);
  }

  /** The ground-truth invariant — asserted after every scenario. Never weaken this. */
  @AfterEach
  void assertReservationInvariant() {
    List<org.jooq.Record> violations =
        dsl
            .fetch(
                "SELECT i.org_id, i.product_id, i.reserved_qty AS reserved, "
                    + "COALESCE((SELECT SUM(r.quantity) FROM inventory_reservation r "
                    + "          WHERE r.org_id = i.org_id AND r.product_id = i.product_id "
                    + "            AND r.status = 'ACTIVE'), 0) AS active_sum "
                    + "FROM inventory i")
            .stream()
            .filter(
                r ->
                    ((Number) r.get("reserved")).longValue()
                        != ((Number) r.get("active_sum")).longValue())
            .toList();
    if (!violations.isEmpty()) {
      fail(
          "Ground-truth invariant violated (reserved_qty != SUM active reservations): "
              + violations);
    }
  }

  // ───────────────────────────── seeding helpers ─────────────────────────────

  protected UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  protected UUID createProduct(UUID orgId, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, sku)
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  /** Create the inventory row with the given on-hand; reserved starts at 0. */
  protected void createInventory(UUID orgId, UUID productId, int stockQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, orgId)
        .set(INVENTORY.PRODUCT_ID, productId)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .set(INVENTORY.VERSION, 0L)
        .execute();
  }

  protected record Line(UUID productId, int quantity) {}

  /**
   * Seed a sales_order in {@code status} with one line + one ACTIVE reservation per {@link Line},
   * and bump each product's {@code inventory.reserved_qty} to keep the ground-truth invariant true
   * at seed time. Returns the order id.
   */
  protected UUID seedOrder(
      UUID orgId, OrderStatus status, OffsetDateTime expiresAt, List<Line> lines) {
    UUID orderId = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-" + orderSeq.getAndIncrement())
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("10.00"))
        .set(SALES_ORDER.EXPIRES_AT, expiresAt)
        .execute();

    for (Line line : lines) {
      UUID lineId = UUID.randomUUID();
      dsl.insertInto(SALES_ORDER_LINE)
          .set(SALES_ORDER_LINE.ID, lineId)
          .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
          .set(SALES_ORDER_LINE.PRODUCT_ID, line.productId())
          .set(SALES_ORDER_LINE.DESCRIPTION, "line")
          .set(SALES_ORDER_LINE.QUANTITY, line.quantity())
          .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
          .set(SALES_ORDER_LINE.LINE_SUBTOTAL, new BigDecimal("10.00"))
          .set(SALES_ORDER_LINE.LINE_TOTAL, new BigDecimal("10.00"))
          .execute();

      dsl.insertInto(INVENTORY_RESERVATION)
          .set(INVENTORY_RESERVATION.ID, UUID.randomUUID())
          .set(INVENTORY_RESERVATION.ORG_ID, orgId)
          .set(INVENTORY_RESERVATION.PRODUCT_ID, line.productId())
          .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
          .set(INVENTORY_RESERVATION.QUANTITY, line.quantity())
          .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
          .set(INVENTORY_RESERVATION.EXPIRES_AT, expiresAt)
          .execute();

      dsl.update(INVENTORY)
          .set(INVENTORY.RESERVED_QTY, INVENTORY.RESERVED_QTY.plus(line.quantity()))
          .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(line.productId())))
          .execute();
    }
    return orderId;
  }

  protected static OffsetDateTime minutesAgo(int m) {
    return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(m);
  }

  protected static OffsetDateTime minutesAhead(int m) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(m);
  }

  // ───────────────────────────── read helpers ─────────────────────────────

  protected String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  protected OffsetDateTime orderExpiredAt(UUID orderId) {
    return dsl.select(SALES_ORDER.EXPIRED_AT)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.EXPIRED_AT);
  }

  protected int reservedQty(UUID orgId, UUID productId) {
    return dsl.select(INVENTORY.RESERVED_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId)))
        .fetchOne(INVENTORY.RESERVED_QTY);
  }

  protected int stockQty(UUID orgId, UUID productId) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId)))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  protected long inventoryVersion(UUID orgId, UUID productId) {
    return dsl.select(INVENTORY.VERSION)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId)))
        .fetchOne(INVENTORY.VERSION);
  }

  protected long activeReservationCount(UUID orderId) {
    return countReservations(orderId, "ACTIVE");
  }

  protected long releasedReservationCount(UUID orderId) {
    return countReservations(orderId, "RELEASED");
  }

  private long countReservations(UUID orderId, String status) {
    return dsl.fetchCount(
        dsl.select(INVENTORY_RESERVATION.ID)
            .from(INVENTORY_RESERVATION)
            .join(SALES_ORDER_LINE)
            .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
            .where(
                SALES_ORDER_LINE
                    .SALES_ORDER_ID
                    .eq(orderId)
                    .and(INVENTORY_RESERVATION.STATUS.eq(ReservationStatus.valueOf(status)))));
  }

  protected List<Record> logRowsForOrder(UUID orderId) {
    return new ArrayList<>(
        dsl.fetch(
            "SELECT product_id, stock_delta, reserved_delta, stock_after, reserved_after,"
                + " reason::text AS reason, actor_id, actor_type::text AS actor_type"
                + " FROM inventory_log WHERE order_id = ?",
            orderId));
  }

  protected void assertReleasedReason(UUID orderId, String expectedReason) {
    List<String> reasons =
        dsl.select(INVENTORY_RESERVATION.RELEASED_REASON)
            .from(INVENTORY_RESERVATION)
            .join(SALES_ORDER_LINE)
            .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
            .where(
                SALES_ORDER_LINE
                    .SALES_ORDER_ID
                    .eq(orderId)
                    .and(INVENTORY_RESERVATION.STATUS.eq(ReservationStatus.RELEASED)))
            .fetch(INVENTORY_RESERVATION.RELEASED_REASON);
    for (String reason : reasons) {
      assertEquals(expectedReason, reason);
    }
  }
}
