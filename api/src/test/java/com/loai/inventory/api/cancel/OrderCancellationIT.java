package com.loai.inventory.api.cancel;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.InvalidOrderTransitionException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderCancellationService.CancelResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration coverage for the order-cancel primitive ({@link OrderCancellationService}): SO →
 * CANCELLED + release reservations + direct-refund every unallocated prepayment, all in one txn.
 * Covers the underpaid-cancel target plus the broader pre-PAID and post-PAID-no-delivery cases the
 * primitive is reused for, and the domain guards. Drives the service directly against the real jOOQ
 * repositories. The {@code reserved_qty == SUM(ACTIVE reservations)} invariant is asserted after
 * every scenario.
 */
@Testcontainers
class OrderCancellationIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static OrderCancellationService service;

  private final AtomicInteger seq = new AtomicInteger(1);

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

    ReservationService reservationService =
        new ReservationService(
            new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl());
    RefundService refundService =
        new RefundService(
            dsl,
            new com.loai.inventory.repository.RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl());
    service =
        new OrderCancellationService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl(),
            reservationService,
            refundService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            new com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl());
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
        "TRUNCATE refund, payment, payment_transaction, inventory_reservation, inventory_log,"
            + " sales_order_line, sales_order, inventory, customer, product, app_user, org"
            + " RESTART IDENTITY CASCADE");
  }

  /** Ground-truth invariant — asserted after every scenario. */
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
    org.junit.jupiter.api.Assertions.assertTrue(
        violations.isEmpty(), "reserved_qty != SUM(ACTIVE reservations): " + violations);
  }

  // scenarios

  @Test
  void underpaidCancel_releasesReservationsAndDirectRefundsPartial() {
    Fixture f = seedOrderWithReservation(OrderStatus.PENDING_PAYMENT, "250.00", "100.00", 25);
    UUID paymentId = seedPrepayment(f, "100.00", PaymentReconciliationStatus.UNDERPAID);

    CancelResult result =
        service.cancel(f.orgId, f.orderId, "changed mind", null, f.adminId, false);

    assertEquals("CANCELLED", orderStatus(f.orderId));
    assertEquals(0, reservedQty(f.orgId, f.productId));
    assertEquals(0, activeReservationCount(f.orderId));
    assertEquals(0, new BigDecimal("100.00").compareTo(result.pendingRefundTotal()));
    assertEquals(1, result.refunds().size());

    // The refund is recorded PENDING only — no money moves: payment stays RECEIVED with its
    // unallocated balance intact, prepaid_amount unchanged, no DEBIT txn. The admin executes later.
    assertEquals("RECEIVED", paymentStatus(paymentId));
    assertEquals(0, new BigDecimal("100.00").compareTo(paymentUnallocated(paymentId)));
    assertEquals(0, new BigDecimal("100.00").compareTo(prepaidAmount(f.orderId)));
    assertEquals(1, pendingRefundCount(paymentId));
    assertEquals(0, executedRefundCount(paymentId));
    assertEquals(0, debitTxnCount(f.orgId));
  }

  @Test
  void paidNoDeliveryCancel_releasesReservationsAndRefundsFullPrepayment() {
    Fixture f = seedOrderWithReservation(OrderStatus.PAID, "250.00", "250.00", 25);
    UUID paymentId = seedPrepayment(f, "250.00", PaymentReconciliationStatus.MATCHED);

    CancelResult result = service.cancel(f.orgId, f.orderId, "fraud", null, f.adminId, false);

    assertEquals("CANCELLED", orderStatus(f.orderId));
    assertEquals(0, reservedQty(f.orgId, f.productId));
    assertEquals(0, new BigDecimal("250.00").compareTo(result.pendingRefundTotal()));
    // PENDING refund: full prepayment recorded as owed, but nothing moves until execute.
    assertEquals("RECEIVED", paymentStatus(paymentId));
    assertEquals(0, new BigDecimal("250.00").compareTo(prepaidAmount(f.orderId)));
    assertEquals(1, pendingRefundCount(paymentId));
    assertEquals(0, debitTxnCount(f.orgId));
  }

  @Test
  void prePaidCancel_releasesReservationsOnly_noRefund() {
    Fixture f = seedOrderWithReservation(OrderStatus.PENDING_PAYMENT, "250.00", "0.00", 25);

    CancelResult result = service.cancel(f.orgId, f.orderId, "ttl", null, f.adminId, false);

    assertEquals("CANCELLED", orderStatus(f.orderId));
    assertEquals(0, reservedQty(f.orgId, f.productId));
    assertEquals(0, activeReservationCount(f.orderId));
    assertEquals(0, result.refunds().size());
    assertEquals(0, BigDecimal.ZERO.compareTo(result.pendingRefundTotal()));
    assertEquals(0, debitTxnCount(f.orgId));
  }

  @Test
  void cancelTwice_secondIsRejected_stateUnchanged() {
    Fixture f = seedOrderWithReservation(OrderStatus.PENDING_PAYMENT, "250.00", "100.00", 25);
    UUID paymentId = seedPrepayment(f, "100.00", PaymentReconciliationStatus.UNDERPAID);

    service.cancel(f.orgId, f.orderId, "first", null, f.adminId, false);

    assertThrows(
        InvalidOrderTransitionException.class,
        () -> service.cancel(f.orgId, f.orderId, "second", null, f.adminId, false));

    // Still exactly one PENDING refund and no DEBIT — the rejected retry rolled back cleanly.
    assertEquals("CANCELLED", orderStatus(f.orderId));
    assertEquals(1, pendingRefundCount(paymentId));
    assertEquals(0, debitTxnCount(f.orgId));
  }

  @Test
  void cancelDeliveredOrder_rejected_nothingReleasedOrRefunded() {
    Fixture f = seedOrderWithReservation(OrderStatus.CLOSED, "250.00", "250.00", 25);

    assertThrows(
        InvalidOrderTransitionException.class,
        () -> service.cancel(f.orgId, f.orderId, "too late", null, f.adminId, false));

    assertEquals("CLOSED", orderStatus(f.orderId));
    assertEquals(25, reservedQty(f.orgId, f.productId));
    assertEquals(0, debitTxnCount(f.orgId));
  }

  @Test
  void cancelUnknownOrder_notFound() {
    UUID orgId = createOrg();
    assertThrows(
        NotFoundException.class,
        () -> service.cancel(orgId, UUID.randomUUID(), "x", null, createUser(orgId), false));
  }

  @Test
  void cancelWithAboveThresholdRefund_nonOwner_rejected_rollsBack() {
    // Org default refund_approval_threshold is 500; a 600 refund must escalate to OWNER.
    Fixture f = seedOrderWithReservation(OrderStatus.PAID, "600.00", "600.00", 60);
    UUID paymentId = seedPrepayment(f, "600.00", PaymentReconciliationStatus.MATCHED);

    assertThrows(
        AuthorizationException.class,
        () -> service.cancel(f.orgId, f.orderId, "big", null, f.adminId, false));

    // Whole cancel rolled back: order still PAID, reservations intact, no refund, no DEBIT.
    assertEquals("PAID", orderStatus(f.orderId));
    assertEquals(60, reservedQty(f.orgId, f.productId));
    assertEquals("RECEIVED", paymentStatus(paymentId));
    assertEquals(0, pendingRefundCount(paymentId));
    assertEquals(0, executedRefundCount(paymentId));
    assertEquals(0, debitTxnCount(f.orgId));
  }

  @Test
  void cancelWithAboveThresholdRefund_owner_succeeds() {
    Fixture f = seedOrderWithReservation(OrderStatus.PAID, "600.00", "600.00", 60);
    UUID paymentId = seedPrepayment(f, "600.00", PaymentReconciliationStatus.MATCHED);

    CancelResult result = service.cancel(f.orgId, f.orderId, "big", null, f.adminId, true);

    assertEquals("CANCELLED", orderStatus(f.orderId));
    assertEquals(0, reservedQty(f.orgId, f.productId));
    assertEquals(0, new BigDecimal("600.00").compareTo(result.pendingRefundTotal()));
    // OWNER clears the above-threshold gate, but the refund is still only PENDING here.
    assertEquals("RECEIVED", paymentStatus(paymentId));
    assertEquals(1, pendingRefundCount(paymentId));
    assertEquals(0, debitTxnCount(f.orgId));
  }

  // seeding

  private record Fixture(UUID orgId, UUID adminId, UUID customerId, UUID productId, UUID orderId) {}

  /** Seed an order in {@code status} with one line + ACTIVE reservation for {@code qty} units. */
  private Fixture seedOrderWithReservation(
      OrderStatus status, String grandTotal, String prepaid, int qty) {
    UUID orgId = createOrg();
    UUID adminId = createUser(orgId);
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, qty + 10, qty);

    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.CUSTOMER_ID, customerId)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-2026-" + String.format("%05d", seq.getAndIncrement()))
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.PREPAID_AMOUNT, new BigDecimal(prepaid))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, lineId)
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, productId)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER_LINE.LINE_TOTAL, new BigDecimal(grandTotal))
        .execute();
    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, UUID.randomUUID())
        .set(INVENTORY_RESERVATION.ORG_ID, orgId)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, productId)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, qty)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .set(INVENTORY_RESERVATION.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).plusHours(24))
        .execute();
    return new Fixture(orgId, adminId, customerId, productId, orderId);
  }

  /** Seed a RECEIVED prepayment Payment (+ its VERIFIED CREDIT transaction) for the order. */
  private UUID seedPrepayment(Fixture f, String amount, PaymentReconciliationStatus recon) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(PAYMENT_TRANSACTION.ORG_ID, f.orgId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.CURRENCY, "EGP")
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.RECONCILIATION_STATUS, recon)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now)
        .set(PAYMENT_TRANSACTION.RECORDED_AT, now)
        .execute();

    UUID paymentId = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, paymentId)
        .set(PAYMENT.ORG_ID, f.orgId)
        .set(PAYMENT.CUSTOMER_ID, f.customerId)
        .set(PAYMENT.SALES_ORDER_ID, f.orderId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.CURRENCY, "EGP")
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.STATUS, PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, now)
        .execute();
    return paymentId;
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, "acme").set(ORG.SLUG, "acme-" + id).execute();
    return id;
  }

  private UUID createUser(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, id + "@c.test")
        .execute();
    return id;
  }

  private UUID createProduct(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, "widget")
        .set(PRODUCT.SKU, "SKU-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private void createInventory(UUID orgId, UUID productId, int stockQty, int reservedQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, orgId)
        .set(INVENTORY.PRODUCT_ID, productId)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, reservedQty)
        .set(INVENTORY.VERSION, 0L)
        .execute();
  }

  // reads

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  private BigDecimal prepaidAmount(UUID orderId) {
    return dsl.select(SALES_ORDER.PREPAID_AMOUNT)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.PREPAID_AMOUNT);
  }

  private int reservedQty(UUID orgId, UUID productId) {
    return dsl.select(INVENTORY.RESERVED_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId)))
        .fetchOne(INVENTORY.RESERVED_QTY);
  }

  private int activeReservationCount(UUID orderId) {
    return dsl.fetchCount(
        dsl.select(INVENTORY_RESERVATION.ID)
            .from(INVENTORY_RESERVATION)
            .join(SALES_ORDER_LINE)
            .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
            .where(
                SALES_ORDER_LINE
                    .SALES_ORDER_ID
                    .eq(orderId)
                    .and(INVENTORY_RESERVATION.STATUS.eq(ReservationStatus.ACTIVE))));
  }

  private String paymentStatus(UUID paymentId) {
    return dsl.select(PAYMENT.STATUS)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.STATUS)
        .getLiteral();
  }

  private BigDecimal paymentUnallocated(UUID paymentId) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
  }

  private int executedRefundCount(UUID paymentId) {
    return dsl.fetchCount(
        dsl.selectFrom(REFUND)
            .where(
                REFUND
                    .PAYMENT_ID
                    .eq(paymentId)
                    .and(
                        REFUND.STATUS.eq(
                            com.loai.inventory.repository.generated.enums.RefundStatus.EXECUTED))));
  }

  private int pendingRefundCount(UUID paymentId) {
    return dsl.fetchCount(
        dsl.selectFrom(REFUND)
            .where(
                REFUND
                    .PAYMENT_ID
                    .eq(paymentId)
                    .and(
                        REFUND.STATUS.eq(
                            com.loai.inventory.repository.generated.enums.RefundStatus.PENDING))));
  }

  private int debitTxnCount(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION)
            .where(
                PAYMENT_TRANSACTION
                    .ORG_ID
                    .eq(orgId)
                    .and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.DEBIT))));
  }
}
