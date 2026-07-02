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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.FulfillmentStatus;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Adversarial integration coverage for {@link OrderCancellationService} — the cases the baseline
 * {@code OrderCancellationIT} does NOT exercise: multi-payment refund loops, multi-product
 * reservation release, cross-tenant isolation, partially-allocated payments (refund only the
 * unallocated remainder), the per-refund (not aggregate) approval threshold, concurrent double
 * cancel under {@code FOR UPDATE}, and the FULFILLING-status guard boundary. Drives the service
 * directly against real jOOQ repositories on Postgres. The {@code reserved_qty == SUM(ACTIVE
 * reservations)} invariant is asserted after every scenario.
 */
@Testcontainers
class OrderCancellationAdversarialIT {

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
            new OrgRepositoryFactoryImpl());
    service =
        new OrderCancellationService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl(),
            reservationService,
            refundService);
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
    assertTrue(violations.isEmpty(), "reserved_qty != SUM(ACTIVE reservations): " + violations);
  }

  // ───────────────────────────── scenarios ─────────────────────────────

  /** Two unallocated prepayments on one order → one PENDING refund each, summed total, no money. */
  @Test
  void multiPayment_twoUnallocatedPrepayments_oneRefundEachNoMoney() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 60, 25);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.PENDING_PAYMENT, "500.00", "250.00");
    seedLineWithReservation(orgId, orderId, productId, 25);
    UUID p1 = seedPayment(orgId, customerId, orderId, "100.00", "100.00", PaymentStatus.RECEIVED);
    UUID p2 = seedPayment(orgId, customerId, orderId, "150.00", "150.00", PaymentStatus.RECEIVED);

    CancelResult result = service.cancel(orgId, orderId, "two partials", null, adminId, false);

    assertEquals("CANCELLED", orderStatus(orderId));
    assertEquals(2, result.refunds().size(), "one PENDING refund per unallocated prepayment");
    assertEquals(0, new BigDecimal("250.00").compareTo(result.pendingRefundTotal()));
    assertEquals(1, pendingRefundCount(p1));
    assertEquals(1, pendingRefundCount(p2));
    // No money moved: both payments keep their full unallocated balance, no DEBIT.
    assertEquals(0, new BigDecimal("100.00").compareTo(paymentUnallocated(p1)));
    assertEquals(0, new BigDecimal("150.00").compareTo(paymentUnallocated(p2)));
    assertEquals(0, debitTxnCount(orgId));
    assertEquals(0, reservedQty(orgId, productId));
  }

  /** Reservations across two distinct products are each released; per-product reserved_qty → 0. */
  @Test
  void multiProduct_releasesEachReservation_invariantHolds() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productA = createProduct(orgId);
    UUID productB = createProduct(orgId);
    createInventory(orgId, productA, 40, 10);
    createInventory(orgId, productB, 40, 7);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.PENDING_PAYMENT, "500.00", "0.00");
    seedLineWithReservation(orgId, orderId, productA, 10);
    seedLineWithReservation(orgId, orderId, productB, 7);

    CancelResult result = service.cancel(orgId, orderId, "ttl", null, adminId, false);

    assertEquals("CANCELLED", orderStatus(orderId));
    assertEquals(2, result.reservationsReleased(), "both reservation rows released");
    assertEquals(0, reservedQty(orgId, productA));
    assertEquals(0, reservedQty(orgId, productB));
    assertEquals(0, activeReservationCount(orderId));
    assertEquals(0, result.refunds().size());
    assertEquals(2, releaseLogCount(orgId), "one RELEASED inventory_log row per product");
  }

  /** Wrong tenant: the order belongs to org A; cancelling under org B is NotFound and a no-op. */
  @Test
  void crossOrg_cancelOrderInAnotherOrg_notFoundAndUntouched() {
    UUID orgA = createOrg();
    UUID orgB = createOrg();
    UUID adminB = createUser();
    UUID customerA = createCustomer(orgA);
    UUID productA = createProduct(orgA);
    createInventory(orgA, productA, 40, 25);
    UUID orderA = seedOrder(orgA, customerA, OrderStatus.PENDING_PAYMENT, "250.00", "100.00");
    seedLineWithReservation(orgA, orderA, productA, 25);
    UUID payA = seedPayment(orgA, customerA, orderA, "100.00", "100.00", PaymentStatus.RECEIVED);

    assertThrows(
        NotFoundException.class,
        () -> service.cancel(orgB, orderA, "wrong tenant", null, adminB, false));

    // Order A is completely untouched: still PENDING_PAYMENT, reservation intact, no refund.
    assertEquals("PENDING_PAYMENT", orderStatus(orderA));
    assertEquals(25, reservedQty(orgA, productA));
    assertEquals(1, activeReservationCount(orderA));
    assertEquals(0, pendingRefundCount(payA));
    assertEquals(0, debitTxnCount(orgA));
  }

  /**
   * A partially-allocated payment is refunded only for its UNALLOCATED remainder, not full amount.
   */
  @Test
  void partiallyAllocatedPayment_refundsOnlyUnallocatedRemainder() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 40, 25);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.PAID, "250.00", "250.00");
    seedLineWithReservation(orgId, orderId, productId, 25);
    // amount 250, but 100 already allocated → only 150 is unallocated and refundable here.
    UUID payId =
        seedPayment(orgId, customerId, orderId, "250.00", "150.00", PaymentStatus.RECEIVED);

    CancelResult result = service.cancel(orgId, orderId, "partial alloc", null, adminId, false);

    assertEquals("CANCELLED", orderStatus(orderId));
    assertEquals(1, result.refunds().size());
    assertEquals(
        0,
        new BigDecimal("150.00").compareTo(result.pendingRefundTotal()),
        "refund is the unallocated remainder (150), not the full payment amount (250)");
    assertEquals(
        0, new BigDecimal("150.00").compareTo(refundAmount(result.refunds().get(0).getId())));
    assertEquals(1, pendingRefundCount(payId));
    assertEquals(0, debitTxnCount(orgId));
  }

  /**
   * The approval gate is on the AGGREGATE, not per refund: two 400 refunds (total 800 &gt; the 500
   * threshold) cannot be split past the OWNER escalation. A non-owner cancel of an order whose
   * prepayments sum above threshold is rejected (closes the structuring hole), and nothing is
   * written — the order stays PAID with its reservation and payments intact.
   */
  @Test
  void aggregateThreshold_twoSubThresholdPayments_nonOwnerRejected() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 100, 80);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.PAID, "800.00", "800.00");
    seedLineWithReservation(orgId, orderId, productId, 80);
    UUID p1 = seedPayment(orgId, customerId, orderId, "400.00", "400.00", PaymentStatus.RECEIVED);
    UUID p2 = seedPayment(orgId, customerId, orderId, "400.00", "400.00", PaymentStatus.RECEIVED);

    // Non-owner (callerIsOwnerOrAdmin=false): aggregate 800 > 500 threshold ⇒ OWNER required.
    assertThrows(
        AuthorizationException.class,
        () -> service.cancel(orgId, orderId, "structured", null, adminId, false));

    // Whole cancel rolled back: nothing refunded, order untouched, reservation still held.
    assertEquals("PAID", orderStatus(orderId));
    assertEquals(0, pendingRefundCount(p1));
    assertEquals(0, pendingRefundCount(p2));
    assertEquals(80, reservedQty(orgId, productId));
  }

  /** The same above-threshold aggregate IS allowed for an OWNER/admin caller. */
  @Test
  void aggregateThreshold_twoSubThresholdPayments_ownerSucceeds() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 100, 80);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.PAID, "800.00", "800.00");
    seedLineWithReservation(orgId, orderId, productId, 80);
    UUID p1 = seedPayment(orgId, customerId, orderId, "400.00", "400.00", PaymentStatus.RECEIVED);
    UUID p2 = seedPayment(orgId, customerId, orderId, "400.00", "400.00", PaymentStatus.RECEIVED);

    CancelResult result = service.cancel(orgId, orderId, "approved", null, adminId, true);

    assertEquals("CANCELLED", orderStatus(orderId));
    assertEquals(2, result.refunds().size());
    assertEquals(0, new BigDecimal("800.00").compareTo(result.pendingRefundTotal()));
    assertEquals(1, pendingRefundCount(p1));
    assertEquals(1, pendingRefundCount(p2));
  }

  /**
   * Two threads cancel the same order at once; FOR UPDATE serializes → exactly one CANCELLED + one
   * refund.
   */
  @Test
  void concurrentCancel_onlyOneSucceeds_singleRefund() throws Exception {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 40, 25);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.PENDING_PAYMENT, "250.00", "100.00");
    seedLineWithReservation(orgId, orderId, productId, 25);
    UUID payId =
        seedPayment(orgId, customerId, orderId, "100.00", "100.00", PaymentStatus.RECEIVED);

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Object> attempt =
        () -> {
          barrier.await();
          try {
            return service.cancel(orgId, orderId, "race", null, adminId, false);
          } catch (RuntimeException e) {
            return e;
          }
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Object> f1 = pool.submit(attempt);
      Future<Object> f2 = pool.submit(attempt);
      Object r1 = f1.get();
      Object r2 = f2.get();

      long successes = List.of(r1, r2).stream().filter(o -> o instanceof CancelResult).count();
      long failures = List.of(r1, r2).stream().filter(o -> o instanceof RuntimeException).count();
      assertEquals(1, successes, "exactly one cancel may win the FOR UPDATE race");
      assertEquals(1, failures, "the loser must be rejected, not double-cancel");
    } finally {
      pool.shutdownNow();
    }

    assertEquals("CANCELLED", orderStatus(orderId));
    assertEquals(1, pendingRefundCount(payId), "the prepayment is refunded exactly once");
    assertEquals(0, debitTxnCount(orgId));
    assertEquals(0, reservedQty(orgId, productId));
  }

  /**
   * A SHIPPED (in-flight) fulfillment blocks the cancel: the goods are neither in the warehouse nor
   * with the customer, so the shipment must first resolve to DELIVERED or FAILED. The cancel rolls
   * back entirely (no refund, reservation untouched, order stays FULFILLING).
   */
  @Test
  void fulfillingOrder_shipmentInFlight_cancelRejected_andRollsBack() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 40, 25);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.FULFILLING, "250.00", "250.00");
    seedLineWithReservation(orgId, orderId, productId, 25);
    seedFulfillment(orgId, orderId, FulfillmentStatus.SHIPPED);
    UUID payId =
        seedPayment(orgId, customerId, orderId, "250.00", "250.00", PaymentStatus.RECEIVED);

    assertThrows(
        ConflictException.class,
        () -> service.cancel(orgId, orderId, "mid-flight", null, adminId, false));

    assertEquals("FULFILLING", orderStatus(orderId), "order must stay FULFILLING");
    assertEquals(25, reservedQty(orgId, productId), "reservation untouched");
    assertEquals(0, pendingRefundCount(payId), "no refund created");
  }

  /**
   * Partial-delivery cancel ({@code salesOrder.md} "→ CANCELLED (post-PAID, partial delivery)"): a
   * FULFILLING order whose only shipment was DELIVERED (and invoiced — its share of the prepayment
   * allocated) cancels cleanly. The un-shipped line's reservation releases and the refund is the
   * UNALLOCATED remainder only — the delivered goods stay sold.
   */
  @Test
  void fulfillingOrder_partialDelivery_cancelRefundsUnallocatedRemainderOnly() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 40, 15);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.FULFILLING, "250.00", "250.00");
    // The un-shipped line still holds its ACTIVE reservation; the delivered line's reservation was
    // consumed at ship, so only this one exists as ACTIVE.
    seedLineWithReservation(orgId, orderId, productId, 15);
    seedFulfillment(orgId, orderId, FulfillmentStatus.DELIVERED);
    // Payment 250; 100 was allocated to the delivered fulfillment's invoice → 150 unallocated.
    // Status is what delivery-time allocation really leaves behind (PARTIALLY_ALLOCATED, not
    // RECEIVED) — pins that findUnallocatedByOrderForUpdate keys on unallocated_amount, not status.
    UUID payId =
        seedPayment(
            orgId, customerId, orderId, "250.00", "150.00", PaymentStatus.PARTIALLY_ALLOCATED);

    CancelResult result = service.cancel(orgId, orderId, "cancel the rest", null, adminId, false);

    assertEquals("CANCELLED", orderStatus(orderId));
    assertEquals(1, result.refunds().size());
    assertEquals(
        0,
        new BigDecimal("150.00").compareTo(result.pendingRefundTotal()),
        "refund is the never-invoiced remainder (150), not the full prepayment (250)");
    assertEquals(1, pendingRefundCount(payId));
    assertEquals(0, reservedQty(orgId, productId), "un-shipped reservation released");
    assertEquals(0, debitTxnCount(orgId), "no money moved — refund is PENDING");
  }

  /**
   * A PENDING (not yet shipped) fulfillment is cascade-cancelled by the order cancel: the order
   * must not leave a live fulfillment behind that could still ship after CANCELLED.
   */
  @Test
  void pendingFulfillment_cascadeCancelledByOrderCancel() {
    UUID orgId = createOrg();
    UUID adminId = createUser();
    UUID customerId = createCustomer(orgId);
    UUID productId = createProduct(orgId);
    createInventory(orgId, productId, 40, 25);
    UUID orderId = seedOrder(orgId, customerId, OrderStatus.PAID, "250.00", "250.00");
    seedLineWithReservation(orgId, orderId, productId, 25);
    UUID fulfillmentId = seedFulfillment(orgId, orderId, FulfillmentStatus.PENDING);
    UUID payId =
        seedPayment(orgId, customerId, orderId, "250.00", "250.00", PaymentStatus.RECEIVED);

    CancelResult result = service.cancel(orgId, orderId, "changed mind", null, adminId, false);

    assertEquals("CANCELLED", orderStatus(orderId));
    assertEquals("CANCELLED", fulfillmentStatus(fulfillmentId), "PENDING fulfillment cascaded");
    assertTrue(fulfillmentCancelledAt(fulfillmentId) != null, "cancelled_at stamped");
    assertEquals(0, reservedQty(orgId, productId));
    assertEquals(1, result.refunds().size());
    assertEquals(1, pendingRefundCount(payId));
  }

  // ───────────────────────────── seeding ─────────────────────────────

  private UUID seedOrder(
      UUID orgId, UUID customerId, OrderStatus status, String grandTotal, String prepaid) {
    UUID orderId = UUID.randomUUID();
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
    return orderId;
  }

  /** Add one order line for {@code qty} units of {@code productId} plus its ACTIVE reservation. */
  private void seedLineWithReservation(UUID orgId, UUID orderId, UUID productId, int qty) {
    UUID lineId = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, lineId)
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, productId)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, new BigDecimal(qty * 10).setScale(2))
        .set(SALES_ORDER_LINE.LINE_TOTAL, new BigDecimal(qty * 10).setScale(2))
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
  }

  /** Seed a bare fulfillment row in {@code status} (lines not needed by the cancel logic). */
  private UUID seedFulfillment(UUID orgId, UUID orderId, FulfillmentStatus status) {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.FULFILLMENT)
        .set(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ID, id)
        .set(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ORG_ID, orgId)
        .set(com.loai.inventory.repository.generated.Tables.FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(com.loai.inventory.repository.generated.Tables.FULFILLMENT.STATUS, status)
        .set(
            com.loai.inventory.repository.generated.Tables.FULFILLMENT.SHIPPED_AT,
            status == FulfillmentStatus.PENDING ? null : now)
        .set(
            com.loai.inventory.repository.generated.Tables.FULFILLMENT.DELIVERED_AT,
            status == FulfillmentStatus.DELIVERED ? now : null)
        .execute();
    return id;
  }

  /**
   * Seed a RECEIVED Payment (+ its VERIFIED CREDIT transaction) with an explicit unallocated
   * balance.
   */
  private UUID seedPayment(
      UUID orgId,
      UUID customerId,
      UUID orderId,
      String amount,
      String unallocated,
      PaymentStatus status) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(PAYMENT_TRANSACTION.ORG_ID, orgId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.CURRENCY, "EGP")
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.RECONCILIATION_STATUS, PaymentReconciliationStatus.MATCHED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now)
        .set(PAYMENT_TRANSACTION.RECORDED_AT, now)
        .execute();

    UUID paymentId = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, paymentId)
        .set(PAYMENT.ORG_ID, orgId)
        .set(PAYMENT.CUSTOMER_ID, customerId)
        .set(PAYMENT.SALES_ORDER_ID, orderId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.CURRENCY, "EGP")
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(unallocated))
        .set(PAYMENT.STATUS, status)
        .set(PAYMENT.RECEIVED_AT, now)
        .execute();
    return paymentId;
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, "acme").set(ORG.SLUG, "acme-" + id).execute();
    return id;
  }

  private UUID createUser() {
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

  // ───────────────────────────── reads ─────────────────────────────

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  private String fulfillmentStatus(UUID fulfillmentId) {
    return dsl.select(com.loai.inventory.repository.generated.Tables.FULFILLMENT.STATUS)
        .from(com.loai.inventory.repository.generated.Tables.FULFILLMENT)
        .where(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ID.eq(fulfillmentId))
        .fetchOne(com.loai.inventory.repository.generated.Tables.FULFILLMENT.STATUS)
        .getLiteral();
  }

  private OffsetDateTime fulfillmentCancelledAt(UUID fulfillmentId) {
    return dsl.select(com.loai.inventory.repository.generated.Tables.FULFILLMENT.CANCELLED_AT)
        .from(com.loai.inventory.repository.generated.Tables.FULFILLMENT)
        .where(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ID.eq(fulfillmentId))
        .fetchOne(com.loai.inventory.repository.generated.Tables.FULFILLMENT.CANCELLED_AT);
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

  private BigDecimal paymentUnallocated(UUID paymentId) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
  }

  private BigDecimal refundAmount(UUID refundId) {
    return dsl.select(REFUND.AMOUNT)
        .from(REFUND)
        .where(REFUND.ID.eq(refundId))
        .fetchOne(REFUND.AMOUNT);
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

  private int releaseLogCount(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectFrom(com.loai.inventory.repository.generated.Tables.INVENTORY_LOG)
            .where(
                com.loai.inventory.repository.generated.Tables.INVENTORY_LOG
                    .ORG_ID
                    .eq(orgId)
                    .and(
                        com.loai.inventory.repository.generated.Tables.INVENTORY_LOG.REASON.eq(
                            com.loai.inventory.repository.generated.enums.StockReason.RELEASED))));
  }
}
