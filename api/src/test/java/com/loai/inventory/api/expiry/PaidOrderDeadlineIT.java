package com.loai.inventory.api.expiry;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.OrderExpiryService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.Record2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reported defect end-to-end ({@code stories/clear_expiry_on_paid.md}): a paid-but-unpacked
 * order must carry NO payment deadline — on the order row or its ACTIVE holds' V19 mirror — and the
 * sweeper must leave it alone once the original deadline passes. The UNDERPAID contrast pins the
 * deliberate other half: a partially-paid order keeps its deadline and still expires.
 *
 * <p>Extends the expiry harness for {@code seedOrder} (order + line + mirrored ACTIVE hold) and the
 * ground-truth invariant ({@code reserved_qty == Σ ACTIVE}), asserted after every scenario.
 */
class PaidOrderDeadlineIT extends ExpiryIntegrationTestBase {

  private final AtomicInteger refSeq = new AtomicInteger(1);

  private PaymentTransactionService txnService;
  private UUID staff;

  @BeforeEach
  void wirePayments() {
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            TestWiring.notificationService(dsl),
            TestWiring.magicLinkService(dsl),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    RefundService refundService =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl());
    txnService =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage(),
            new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.UserRepositoryFactoryImpl());
    staff = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, staff)
        .set(APP_USER.EMAIL, staff + "@expiry.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
  }

  // scenarios

  @Test
  void exactCover_clearsBothDeadlines_andTheSweeperLeavesThePaidOrderAlone() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    // Deadline already in the past — the razor's edge: without the fix this order would render
    // "Expired" holds forever; without the sweeper's status guard it would BE expired.
    UUID orderId =
        seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAgo(5), List.of(new Line(product, 1)));

    var outcome = verify(org, orderId, "10.00");

    assertEquals(PaymentReconciliationStatus.MATCHED, outcome);
    assertOrder(orderId, OrderStatus.PAID, null);
    assertSingleHold(orderId, ReservationStatus.ACTIVE, false);

    // The user's scenario: the deadline has passed. The sweeper must not see a candidate.
    OrderExpiryService.Summary summary = expiryService.sweep(100);
    assertEquals(0, summary.candidatesScanned(), "a PAID order is never an expiry candidate");
    assertOrder(orderId, OrderStatus.PAID, null);
    assertSingleHold(orderId, ReservationStatus.ACTIVE, false);
  }

  @Test
  void overpaid_clearsBothDeadlines() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    UUID orderId =
        seedOrder(
            org, OrderStatus.PENDING_PAYMENT, minutesAhead(60), List.of(new Line(product, 1)));

    var outcome = verify(org, orderId, "15.00");

    assertEquals(PaymentReconciliationStatus.OVERPAID, outcome);
    assertOrder(orderId, OrderStatus.PAID, null);
    assertSingleHold(orderId, ReservationStatus.ACTIVE, false);
  }

  @Test
  void underpaid_keepsBothDeadlines_andStillExpires() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    UUID orderId =
        seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAgo(5), List.of(new Line(product, 1)));

    var outcome = verify(org, orderId, "4.00");

    // The deliberate contrast: partially paid stays expirable, so its deadline is still true.
    assertEquals(PaymentReconciliationStatus.UNDERPAID, outcome);
    assertOrder(orderId, OrderStatus.PENDING_PAYMENT, minutesAgo(0) /* any non-null */);
    assertSingleHold(orderId, ReservationStatus.ACTIVE, true);

    OrderExpiryService.Summary summary = expiryService.sweep(100);
    assertEquals(1, summary.ordersExpired(), "an underpaid order still expires (documented v1)");
    assertOrder(orderId, OrderStatus.EXPIRED, minutesAgo(0));
    assertSingleHold(orderId, ReservationStatus.RELEASED, true);
  }

  // helpers

  private PaymentReconciliationStatus verify(UUID org, UUID orderId, String amount) {
    String number =
        dsl.select(SALES_ORDER.ORDER_NUMBER)
            .from(SALES_ORDER)
            .where(SALES_ORDER.ID.eq(orderId))
            .fetchSingle()
            .value1();
    return txnService
        .verify(
            org,
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                "IPN-" + refSeq.getAndIncrement(),
                new BigDecimal(amount),
                "EGP",
                null,
                number,
                null,
                null,
                null,
                minutesAgo(1)),
            staff)
        .reconciliationStatus();
  }

  /** Assert order status + whether a deadline is present (pass null to demand absence). */
  private void assertOrder(UUID orderId, OrderStatus status, Object expiresAtPresence) {
    var row =
        dsl.select(SALES_ORDER.STATUS, SALES_ORDER.EXPIRES_AT)
            .from(SALES_ORDER)
            .where(SALES_ORDER.ID.eq(orderId))
            .fetchSingle();
    assertEquals(status, row.value1());
    if (expiresAtPresence == null) {
      assertNull(row.value2(), "a paid order must carry no payment deadline");
    } else {
      assertNotNull(row.value2(), "a still-expirable order keeps its deadline");
    }
  }

  /** Assert the order's single hold: status + whether the V19 expiry mirror is present. */
  private void assertSingleHold(UUID orderId, ReservationStatus status, boolean mirrorPresent) {
    Record2<ReservationStatus, java.time.OffsetDateTime> row =
        dsl.select(INVENTORY_RESERVATION.STATUS, INVENTORY_RESERVATION.EXPIRES_AT)
            .from(INVENTORY_RESERVATION)
            .join(SALES_ORDER_LINE)
            .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
            .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(orderId))
            .fetchSingle();
    assertEquals(status, row.value1());
    if (mirrorPresent) {
      assertNotNull(row.value2(), "the hold keeps mirroring a live deadline");
    } else {
      assertNull(row.value2(), "a paid order's hold must not assert a dead deadline");
    }
  }
}
