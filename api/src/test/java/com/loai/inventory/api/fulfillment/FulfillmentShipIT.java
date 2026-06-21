package com.loai.inventory.api.fulfillment;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Integration coverage for the ship-a-fulfillment slice ({@code stories/ship_fulfillment.md}).
 * Boots one PostgreSQL container, runs all Flyway migrations, and drives {@link FulfillmentService}
 * against the real jOOQ repository factories — no Tomcat/Redis/JWT.
 *
 * <p>The headline property: shipping a paid order's lines decrements {@code on_hand} in real time,
 * consumes the reservation, leaves {@code available} unchanged, and flips the order to FULFILLING.
 */
@Testcontainers
class FulfillmentShipIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static FulfillmentService service;

  private final AtomicInteger seq = new AtomicInteger(1);
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
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchema() {
    dsl.execute(
        "TRUNCATE fulfillment, fulfillment_line, inventory_reservation, inventory_log, inventory,"
            + " sales_order_line, sales_order, product, org RESTART IDENTITY CASCADE");
  }

  // ───────────────────────────── scenarios ─────────────────────────────

  @Test
  void shipFullLine_decrementsOnHand_consumesReservation_orderFulfilling() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order = seedPaidOrder(org, List.of(new Want(product, 3)));
    Line line = order.lines().get(0);

    FulfillmentView created =
        service.create(
            org, order.id(), List.of(new LineInput(line.lineId())), null, null, null, actor);
    assertEquals("PENDING", created.fulfillment().getStatus().name());

    FulfillmentView shipped = service.ship(org, created.fulfillment().getId(), actor);

    assertEquals("SHIPPED", shipped.fulfillment().getStatus().name());
    assertNotNull(shipped.fulfillment().getShippedAt());

    // on_hand drops by 3, reserved drops by 3 → available (= 7) unchanged.
    assertEquals(7, stockQty(org, product));
    assertEquals(0, reservedQty(org, product));

    assertEquals("CONSUMED", reservationStatus(line.reservationId()));
    assertEquals("FULFILLING", orderStatus(order.id()));

    // Exactly one SOLD inventory_log row with the right deltas + order id.
    var logs = dsl.selectFrom(INVENTORY_LOG).where(INVENTORY_LOG.PRODUCT_ID.eq(product)).fetch();
    assertEquals(1, logs.size());
    var row = logs.get(0);
    assertEquals("SOLD", row.getReason().getLiteral());
    assertEquals(-3, row.getStockDelta());
    assertEquals(-3, row.getReservedDelta());
    assertEquals(7, row.getStockAfter());
    assertEquals(0, row.getReservedAfter());
    assertEquals(order.id(), row.getOrderId());
  }

  @Test
  void shipOneLineOfTwo_onlyThatProductDecrements_otherStaysReserved() {
    UUID org = createOrg("acme");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    createInventory(org, a, 10, 2);
    createInventory(org, b, 5, 4);
    Order order = seedPaidOrder(org, List.of(new Want(a, 2), new Want(b, 4)));
    Line lineA = order.lines().get(0);
    Line lineB = order.lines().get(1);

    // Ship only line A.
    FulfillmentView fa =
        service.create(
            org, order.id(), List.of(new LineInput(lineA.lineId())), null, null, null, actor);
    service.ship(org, fa.fulfillment().getId(), actor);

    assertEquals(8, stockQty(org, a));
    assertEquals(0, reservedQty(org, a));
    assertEquals("CONSUMED", reservationStatus(lineA.reservationId()));

    // B is untouched and still reserved.
    assertEquals(5, stockQty(org, b));
    assertEquals(4, reservedQty(org, b));
    assertEquals("ACTIVE", reservationStatus(lineB.reservationId()));

    // Order moved PAID → FULFILLING on the first shipment.
    assertEquals("FULFILLING", orderStatus(order.id()));

    // Ship the rest (line B). Order stays FULFILLING (FULFILLED is a later slice, at DELIVERED).
    FulfillmentView fb =
        service.create(
            org, order.id(), List.of(new LineInput(lineB.lineId())), null, null, null, actor);
    service.ship(org, fb.fulfillment().getId(), actor);

    assertEquals(1, stockQty(org, b));
    assertEquals(0, reservedQty(org, b));
    assertEquals("CONSUMED", reservationStatus(lineB.reservationId()));
    assertEquals("FULFILLING", orderStatus(order.id()));
  }

  @Test
  void shipAlreadyShippedFulfillment_isRejected() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order = seedPaidOrder(org, List.of(new Want(product, 3)));
    Line line = order.lines().get(0);

    Fulfillment f =
        service
            .create(org, order.id(), List.of(new LineInput(line.lineId())), null, null, null, actor)
            .fulfillment();
    service.ship(org, f.getId(), actor);

    assertThrows(ConflictException.class, () -> service.ship(org, f.getId(), actor));

    // Stock only decremented once despite the second (failed) ship attempt.
    assertEquals(7, stockQty(org, product));
    assertEquals(0, reservedQty(org, product));
  }

  @Test
  void createForUnpaidOrder_isRejected() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order = seedPendingOrder(org, List.of(new Want(product, 3)));
    Line line = order.lines().get(0);

    assertThrows(
        ValidationException.class,
        () ->
            service.create(
                org, order.id(), List.of(new LineInput(line.lineId())), null, null, null, actor));
  }

  @Test
  void refulfillingAnAlreadyShippedLine_isRejected() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order = seedPaidOrder(org, List.of(new Want(product, 3)));
    Line line = order.lines().get(0);

    Fulfillment f =
        service
            .create(org, order.id(), List.of(new LineInput(line.lineId())), null, null, null, actor)
            .fulfillment();
    service.ship(org, f.getId(), actor);

    // The line's reservation is now CONSUMED — a second fulfillment for it has nothing to claim.
    assertThrows(
        ValidationException.class,
        () ->
            service.create(
                org, order.id(), List.of(new LineInput(line.lineId())), null, null, null, actor));
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private record Want(UUID productId, int qty) {}

  private record Line(UUID lineId, UUID productId, int qty, UUID reservationId) {}

  private record Order(UUID id, String number, List<Line> lines) {}

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

  private void createInventory(UUID org, UUID product, int stockQty, int reservedQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, reservedQty)
        .execute();
  }

  private Order seedPaidOrder(UUID org, List<Want> wants) {
    return seedOrder(org, OrderStatus.PAID, wants, true);
  }

  private Order seedPendingOrder(UUID org, List<Want> wants) {
    return seedOrder(org, OrderStatus.PENDING_PAYMENT, wants, false);
  }

  private Order seedOrder(UUID org, OrderStatus status, List<Want> wants, boolean reserve) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", seq.getAndIncrement());
    BigDecimal grand = BigDecimal.ZERO;
    for (Want w : wants) {
      grand = grand.add(new BigDecimal("10.00").multiply(BigDecimal.valueOf(w.qty())));
    }
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.SUBTOTAL, grand)
        .set(SALES_ORDER.GRAND_TOTAL, grand)
        .set(SALES_ORDER.PREPAID_AMOUNT, status == OrderStatus.PAID ? grand : BigDecimal.ZERO)
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();

    List<Line> lines = new ArrayList<>(wants.size());
    for (Want w : wants) {
      UUID lineId = UUID.randomUUID();
      BigDecimal lineTotal = new BigDecimal("10.00").multiply(BigDecimal.valueOf(w.qty()));
      dsl.insertInto(SALES_ORDER_LINE)
          .set(SALES_ORDER_LINE.ID, lineId)
          .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
          .set(SALES_ORDER_LINE.PRODUCT_ID, w.productId())
          .set(SALES_ORDER_LINE.DESCRIPTION, "line")
          .set(SALES_ORDER_LINE.QUANTITY, w.qty())
          .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
          .set(SALES_ORDER_LINE.LINE_SUBTOTAL, lineTotal)
          .set(SALES_ORDER_LINE.LINE_TOTAL, lineTotal)
          .execute();

      UUID reservationId = null;
      if (reserve) {
        reservationId = UUID.randomUUID();
        dsl.insertInto(INVENTORY_RESERVATION)
            .set(INVENTORY_RESERVATION.ID, reservationId)
            .set(INVENTORY_RESERVATION.ORG_ID, org)
            .set(INVENTORY_RESERVATION.PRODUCT_ID, w.productId())
            .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
            .set(INVENTORY_RESERVATION.QUANTITY, w.qty())
            .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
            .execute();
      }
      lines.add(new Line(lineId, w.productId(), w.qty(), reservationId));
    }
    return new Order(orderId, number, lines);
  }

  private int stockQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private int reservedQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.RESERVED_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.RESERVED_QTY);
  }

  private String reservationStatus(UUID reservationId) {
    return dsl.select(INVENTORY_RESERVATION.STATUS)
        .from(INVENTORY_RESERVATION)
        .where(INVENTORY_RESERVATION.ID.eq(reservationId))
        .fetchOne(INVENTORY_RESERVATION.STATUS)
        .getLiteral();
  }

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }
}
