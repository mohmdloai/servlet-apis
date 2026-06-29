package com.loai.inventory.api.fulfillment;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.FailedRefundResult;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.RefundService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Integration coverage for the failed-fulfillment slice ({@code
 * sys-analysis/outbound/fulfillment.md} §FAILED). Boots one PostgreSQL container and drives {@link
 * FulfillmentService} + {@link RefundService} against the real jOOQ factories.
 *
 * <p>The shape under test: a SHIPPED fulfillment that never arrives moves to FAILED (no stock or
 * money moves), then the admin resolves it either by a direct payment-backed refund (no invoice
 * exists at SHIPPED, so it cannot be CreditNote-backed) or by recording the goods back in stock.
 */
@Testcontainers
class FailedFulfillmentIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static FulfillmentService service;
  static RefundService refundService;

  /**
   * A real app_user — payment_transaction.verified_by FKs to it. Seeded once, survives truncate.
   */
  static UUID actorId;

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

    refundService =
        new RefundService(
            dsl,
            new com.loai.inventory.repository.RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl());
    service =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            new com.loai.inventory.service.InvoiceService(
                new com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl()),
            refundService);

    actorId = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.APP_USER)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.ID, actorId)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.EMAIL, "ff-admin@test")
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.PASSWORD_HASH, "x")
        .set(
            com.loai.inventory.repository.generated.Tables.APP_USER.ACTOR_TYPE,
            com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
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
        "TRUNCATE refund_allocation, refund, credit_note, payment_allocation, sales_invoice_line,"
            + " sales_invoice, payment, payment_transaction, fulfillment, fulfillment_line,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, org, invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // ───────────────────────────── happy paths ─────────────────────────────

  @Test
  void fail_shippedFulfillment_marksFailed_noStockOrOrderMove() {
    Setup s = shipOne("30.00", 3, 10);

    var view = service.markFailed(s.org, s.fulfillmentId, "lost in transit");

    assertEquals("FAILED", view.fulfillment().getStatus().name());
    assertNotNull(view.fulfillment().getFailedAt());
    assertEquals("lost in transit", view.fulfillment().getFailedReason());

    // No stock moved at FAILED: on_hand stays at its post-ship value, the order stays FULFILLING.
    assertEquals(7, stockQty(s.org, s.product));
    assertEquals(0, reservedQty(s.org, s.product));
    assertEquals("FULFILLING", orderStatus(s.orderId));

    // Only the SHIPPED-time SOLD row exists — failure writes no inventory_log row.
    assertEquals(1, dsl.fetchCount(INVENTORY_LOG, INVENTORY_LOG.PRODUCT_ID.eq(s.product)));
  }

  @Test
  void refund_failedFulfillment_createsPendingDirectRefund_executeDrainsPayment() {
    Setup s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "refused");

    FailedRefundResult result = service.refundFailed(s.org, s.fulfillmentId, null, actorId, false);

    assertEquals(1, result.refunds().size());
    assertEquals(0, new BigDecimal("30.00").compareTo(result.pendingRefundTotal()));
    UUID refundId = result.refunds().get(0).getId();
    // PENDING refund records the obligation but moves no money yet.
    assertEquals("PENDING", refundStatus(refundId));
    assertEquals(0, new BigDecimal("30.00").compareTo(paymentUnallocated(s.paymentId)));

    // Admin performs the real reverse transfer, then executes — now the money moves.
    refundService.execute(s.org, refundId, "IP-FF-1", actorId);

    assertEquals("EXECUTED", refundStatus(refundId));
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(s.paymentId)));
    assertEquals("REFUNDED", paymentStatus(s.paymentId));
  }

  @Test
  void refund_oneOfTwoFulfillments_refundsOnlyThatFulfillmentsValue_leavesOthersFundingIntact() {
    // Two-line order, one fulfillment per line: A = 3 × 10.00 = 30.00, B = 4 × 10.00 = 40.00,
    // fully prepaid 70.00. Fulfillment A ships and fails; B is still in flight.
    TwoFulfillments s = seedTwoFulfillmentOrder();
    service.ship(s.org, s.fulfA, actor);
    service.ship(s.org, s.fulfB, actor);
    service.markFailed(s.org, s.fulfA, "lost in transit");

    // THE FIX: refund only A's value (30.00) — NOT the order's whole 70.00 unallocated prepayment
    // (that is an order cancel, and would strip B's funding). The pre-fix code refunded 70.00.
    FailedRefundResult result = service.refundFailed(s.org, s.fulfA, null, actorId, false);
    assertEquals(1, result.refunds().size());
    assertEquals(
        0,
        new BigDecimal("30.00").compareTo(result.pendingRefundTotal()),
        "must refund only the failed fulfillment's value, not the whole prepayment");

    // Execute it: the payment drains by exactly 30, leaving 40 — fulfillment B's funding survives.
    refundService.execute(s.org, result.refunds().get(0).getId(), "IP-MF-1", actorId);
    assertEquals(
        0,
        new BigDecimal("40.00").compareTo(paymentUnallocated(s.paymentId)),
        "fulfillment B's prepayment must remain intact");

    // End-to-end proof: B can still be delivered, invoiced for 40.00, and fully paid from the
    // surviving prepayment — which the old all-unallocated refund would have made impossible.
    var delivered = service.markDelivered(s.org, s.fulfB, actor);
    assertEquals(0, new BigDecimal("40.00").compareTo(delivered.invoice().getGrandTotal()));
    assertEquals("PAID", delivered.invoice().getStatus().name());
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(s.paymentId)));
  }

  @Test
  void return_failedFulfillment_restocksGoods_andIsIdempotent() {
    Setup s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "returned to sender");

    var view = service.recordReturn(s.org, s.fulfillmentId, actor);

    // +3 back to on_hand (7 → 10); reserved untouched; returned_at stamped.
    assertEquals(10, stockQty(s.org, s.product));
    assertEquals(0, reservedQty(s.org, s.product));
    assertNotNull(view.fulfillment().getReturnedAt());
    assertNotNull(returnedAt(s.fulfillmentId));

    // A RESTOCKED_FAILED_FULFILLMENT row records the +stock, referencing the order.
    var restockRow =
        dsl.selectFrom(INVENTORY_LOG)
            .where(
                INVENTORY_LOG
                    .PRODUCT_ID
                    .eq(s.product)
                    .and(INVENTORY_LOG.REASON.eq(reason("RESTOCKED_FAILED_FULFILLMENT"))))
            .fetchOne();
    assertNotNull(restockRow);
    assertEquals(3, restockRow.getStockDelta());
    assertEquals(0, restockRow.getReservedDelta());
    assertEquals(10, restockRow.getStockAfter());
    assertEquals(s.orderId, restockRow.getOrderId());

    // A second return is rejected — the same goods can't be restocked twice.
    assertThrows(
        ConflictException.class, () -> service.recordReturn(s.org, s.fulfillmentId, actor));
    assertEquals(10, stockQty(s.org, s.product));
  }

  @Test
  void refund_aboveThreshold_requiresOwner() {
    // grand 600 > the org's default 500 refund-approval threshold.
    Setup s = shipOne("600.00", 60, 65);
    service.markFailed(s.org, s.fulfillmentId, "lost");

    // Non-owner is blocked by the OWNER gate; the whole refund rolls back (no refund row written).
    assertThrows(
        AuthorizationException.class,
        () -> service.refundFailed(s.org, s.fulfillmentId, null, actorId, false));
    assertEquals(0, dsl.fetchCount(REFUND));

    // Owner clears the gate.
    FailedRefundResult ok = service.refundFailed(s.org, s.fulfillmentId, null, actorId, true);
    assertEquals(1, ok.refunds().size());
    assertEquals(0, new BigDecimal("600.00").compareTo(ok.pendingRefundTotal()));
  }

  // ───────────────────────────── adversarial ─────────────────────────────

  @Test
  void fail_pendingFulfillment_isRejected() {
    Setup s = createOne("30.00", 3, 10); // created PENDING, never shipped
    assertThrows(
        ConflictException.class, () -> service.markFailed(s.org, s.fulfillmentId, "too early"));
  }

  @Test
  void fail_deliveredFulfillment_isRejected() {
    Setup s = shipOne("30.00", 3, 10);
    service.markDelivered(s.org, s.fulfillmentId, actor); // SHIPPED → DELIVERED (terminal)
    assertThrows(
        ConflictException.class, () -> service.markFailed(s.org, s.fulfillmentId, "too late"));
  }

  @Test
  void fail_twice_isRejected() {
    Setup s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "lost");
    assertThrows(
        ConflictException.class, () -> service.markFailed(s.org, s.fulfillmentId, "again"));
  }

  @Test
  void refund_beforeFail_isRejected() {
    Setup s = shipOne("30.00", 3, 10); // SHIPPED, not FAILED
    assertThrows(
        ConflictException.class,
        () -> service.refundFailed(s.org, s.fulfillmentId, null, actorId, false));
  }

  @Test
  void return_beforeFail_isRejected() {
    Setup s = shipOne("30.00", 3, 10); // SHIPPED, not FAILED
    assertThrows(
        ConflictException.class, () -> service.recordReturn(s.org, s.fulfillmentId, actor));
  }

  // ───────────────────────────── fixtures ─────────────────────────────

  private record Setup(
      UUID org, UUID product, UUID orderId, UUID paymentId, UUID lineId, UUID fulfillmentId) {}

  /** Seed a paid online order with one line + prepayment, then create a PENDING fulfillment. */
  private Setup createOne(String grandTotal, int qty, int stockOnHand) {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org);
    UUID product = createProduct(org, "SKU");
    createInventory(org, product, stockOnHand, qty);
    Seeded seeded = seedPaidOrder(org, customer, product, qty, grandTotal);
    UUID f =
        service
            .create(
                org, seeded.orderId, List.of(new LineInput(seeded.lineId)), null, null, null, actor)
            .fulfillment()
            .getId();
    return new Setup(org, product, seeded.orderId, seeded.paymentId, seeded.lineId, f);
  }

  /** Like {@link #createOne} but also ships it (PENDING → SHIPPED, -stock written). */
  private Setup shipOne(String grandTotal, int qty, int stockOnHand) {
    Setup s = createOne(grandTotal, qty, stockOnHand);
    service.ship(s.org, s.fulfillmentId, actor);
    return s;
  }

  private record Seeded(UUID orderId, UUID lineId, UUID paymentId) {}

  private Seeded seedPaidOrder(UUID org, UUID customer, UUID product, int qty, String grandTotal) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    BigDecimal grand = new BigDecimal(grandTotal);
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PAID)
        .set(SALES_ORDER.SUBTOTAL, grand)
        .set(SALES_ORDER.GRAND_TOTAL, grand)
        .set(SALES_ORDER.PREPAID_AMOUNT, grand)
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();

    UUID lineId = UUID.randomUUID();
    BigDecimal lineTotal = new BigDecimal("10.00").multiply(BigDecimal.valueOf(qty));
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, lineId)
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, product)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, lineTotal)
        .set(SALES_ORDER_LINE.LINE_TOTAL, lineTotal)
        .execute();

    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, UUID.randomUUID())
        .set(INVENTORY_RESERVATION.ORG_ID, org)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, product)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, qty)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .execute();

    UUID paymentId = seedPayment(org, customer, orderId, grandTotal);
    return new Seeded(orderId, lineId, paymentId);
  }

  private record TwoFulfillments(UUID org, UUID orderId, UUID paymentId, UUID fulfA, UUID fulfB) {}

  /**
   * Seed a paid online order with two lines (A: 3 × 10.00 = 30.00, B: 4 × 10.00 = 40.00; grand
   * 70.00, fully prepaid) and a PENDING fulfillment per line — the multi-fulfillment shape where a
   * failed-fulfillment refund must touch only its own slice of the prepayment.
   */
  private TwoFulfillments seedTwoFulfillmentOrder() {
    UUID org = createOrg("multi");
    UUID customer = createCustomer(org);
    UUID productA = createProduct(org, "A");
    UUID productB = createProduct(org, "B");
    createInventory(org, productA, 10, 3);
    createInventory(org, productB, 10, 4);

    UUID orderId = UUID.randomUUID();
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PAID)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal("70.00"))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("70.00"))
        .set(SALES_ORDER.PREPAID_AMOUNT, new BigDecimal("70.00"))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();

    UUID lineA = insertOrderLine(org, orderId, productA, 3);
    UUID lineB = insertOrderLine(org, orderId, productB, 4);
    UUID paymentId = seedPayment(org, customer, orderId, "70.00");

    UUID fulfA =
        service
            .create(org, orderId, List.of(new LineInput(lineA)), null, null, null, actor)
            .fulfillment()
            .getId();
    UUID fulfB =
        service
            .create(org, orderId, List.of(new LineInput(lineB)), null, null, null, actor)
            .fulfillment()
            .getId();
    return new TwoFulfillments(org, orderId, paymentId, fulfA, fulfB);
  }

  /** Insert one order line (unit 10.00) plus its ACTIVE reservation; returns the line id. */
  private UUID insertOrderLine(UUID org, UUID orderId, UUID product, int qty) {
    UUID lineId = UUID.randomUUID();
    BigDecimal lineTotal = new BigDecimal("10.00").multiply(BigDecimal.valueOf(qty));
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, lineId)
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, product)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, lineTotal)
        .set(SALES_ORDER_LINE.LINE_TOTAL, lineTotal)
        .execute();
    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, UUID.randomUUID())
        .set(INVENTORY_RESERVATION.ORG_ID, org)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, product)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, qty)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .execute();
    return lineId;
  }

  private UUID seedPayment(UUID org, UUID customer, UUID orderId, String amount) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusHours(2))
        .execute();

    UUID paymentId = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, paymentId)
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.CUSTOMER_ID, customer)
        .set(PAYMENT.SALES_ORDER_ID, orderId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.STATUS, PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, now().minusHours(2))
        .execute();
    return paymentId;
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

  private UUID createCustomer(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, id + "@acme.test")
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

  // ───────────────────────────── query helpers ─────────────────────────────

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static com.loai.inventory.repository.generated.enums.StockReason reason(String name) {
    return com.loai.inventory.repository.generated.enums.StockReason.valueOf(name);
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

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  private OffsetDateTime returnedAt(UUID fulfillmentId) {
    return dsl.select(FULFILLMENT.RETURNED_AT)
        .from(FULFILLMENT)
        .where(FULFILLMENT.ID.eq(fulfillmentId))
        .fetchOne(FULFILLMENT.RETURNED_AT);
  }

  private String refundStatus(UUID id) {
    return dsl.select(REFUND.STATUS)
        .from(REFUND)
        .where(REFUND.ID.eq(id))
        .fetchOne(REFUND.STATUS)
        .getLiteral();
  }

  private String paymentStatus(UUID id) {
    return dsl.select(PAYMENT.STATUS)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.STATUS)
        .getLiteral();
  }

  private BigDecimal paymentUnallocated(UUID id) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
  }
}
