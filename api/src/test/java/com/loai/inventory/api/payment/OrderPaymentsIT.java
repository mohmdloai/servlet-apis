package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentStatus;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.RefundStatus;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
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
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderCancellationService.CancelResult;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.OrderPayments;
import com.loai.inventory.service.PaymentService.PaymentWithRefunds;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Integration coverage for the order money story ({@code stories/list_order_payments.md}): {@code
 * PaymentService.listForOrder} reconstructs {@code prepaid_amount} from its constituent payments —
 * UNDERPAID top-up sequences FIFO, overpaid excess on {@code unallocated_amount}, cancel-created
 * PENDING direct refunds, executed refunds, disputes.
 *
 * <p>Drives the services directly against the real jOOQ repositories — no Tomcat. VIEWER
 * authorization is enforced in the handler ({@code OrderPaymentsHandlerAuthTest}), as everywhere.
 */
@Testcontainers
class OrderPaymentsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PaymentService paymentService;
  static PaymentTransactionService txnService;
  static RefundService refundService;
  static OrderCancellationService cancellationService;

  private final AtomicInteger orderSeq = new AtomicInteger(1);
  private final AtomicInteger refSeq = new AtomicInteger(1);
  private final AtomicInteger occurredSeq = new AtomicInteger(1);
  private final OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

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

    paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    refundService =
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
            TestWiring.storage());
    cancellationService =
        new OrderCancellationService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new FulfillmentRepositoryFactoryImpl(),
            new ReservationService(
                new InventoryRepositoryFactoryImpl(),
                new InventoryReservationRepositoryFactoryImpl(),
                new InventoryLogRepositoryFactoryImpl()),
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
        "TRUNCATE refund, payment, payment_transaction, sales_order_line, sales_order, customer,"
            + " product, app_user, org RESTART IDENTITY CASCADE");
  }

  // the money story

  @Test
  void underpaidTopUpSequence_threePaymentsFifo_sumReconstructsPrepaid_orderPaid() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "1000.00");
    verify(orgId, admin, "400.00", order.number(), "UNDERPAID");
    verify(orgId, admin, "300.00", order.number(), "UNDERPAID");
    verify(orgId, admin, "300.00", order.number(), "MATCHED");

    OrderPayments result = paymentService.listForOrder(orgId, order.id());

    assertEquals(OrderStatus.PAID, result.order().getStatus());
    assertEquals(3, result.payments().size());
    BigDecimal sum =
        result.payments().stream()
            .map(p -> p.payment().getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, result.order().getPrepaidAmount().compareTo(sum));
    for (int i = 1; i < result.payments().size(); i++) {
      Payment prev = result.payments().get(i - 1).payment();
      Payment cur = result.payments().get(i).payment();
      assertTrue(
          !prev.getReceivedAt().isAfter(cur.getReceivedAt()),
          "payments must be received_at ASC (the allocation FIFO)");
    }
    // Refund arrays are present-but-empty on a clean money story.
    assertTrue(result.payments().stream().allMatch(p -> p.refunds().isEmpty()));
  }

  @Test
  void overpaid_singlePayment_fullAmountStillUnallocated() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "500.00");
    verify(orgId, admin, "600.00", order.number(), "OVERPAID");

    OrderPayments result = paymentService.listForOrder(orgId, order.id());

    assertEquals(OrderStatus.PAID, result.order().getStatus());
    assertEquals(1, result.payments().size());
    Payment p = result.payments().get(0).payment();
    assertEquals(0, new BigDecimal("600.00").compareTo(p.getAmount()));
    // Nothing is invoice-allocated pre-delivery, so the whole amount (incl. the 100.00 excess the
    // screen derives as prepaid − grand_total) sits on unallocated_amount.
    assertEquals(0, new BigDecimal("600.00").compareTo(p.getUnallocatedAmount()));
  }

  @Test
  void cancelledOrder_eachPaymentCarriesItsPendingRefund_executedOneShowsRefundedAmount() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "1000.00");
    verify(orgId, admin, "400.00", order.number(), "UNDERPAID");
    verify(orgId, admin, "200.00", order.number(), "UNDERPAID");

    CancelResult cancelled =
        cancellationService.cancel(orgId, order.id(), "customer bailed", null, admin, true);
    assertEquals(2, cancelled.refunds().size());

    OrderPayments afterCancel = paymentService.listForOrder(orgId, order.id());
    assertEquals(OrderStatus.CANCELLED, afterCancel.order().getStatus());
    assertEquals(2, afterCancel.payments().size());
    for (PaymentWithRefunds p : afterCancel.payments()) {
      assertEquals(1, p.refunds().size());
      Refund refund = p.refunds().get(0);
      assertEquals(RefundStatus.PENDING, refund.getStatus());
      assertEquals(0, p.payment().getAmount().compareTo(refund.getAmount()));
    }

    // Execute the first refund — the payment now shows the money that left.
    Refund first = afterCancel.payments().get(0).refunds().get(0);
    refundService.execute(orgId, first.getId(), "IP-RETURN-" + refSeq.getAndIncrement(), admin);

    OrderPayments afterExecute = paymentService.listForOrder(orgId, order.id());
    PaymentWithRefunds refunded = afterExecute.payments().get(0);
    assertEquals(PaymentStatus.REFUNDED, refunded.payment().getStatus());
    assertEquals(
        0, refunded.payment().getAmount().compareTo(refunded.payment().getRefundedAmount()));
    assertEquals(RefundStatus.EXECUTED, refunded.refunds().get(0).getStatus());
    // The other payment still awaits its execution.
    assertEquals(RefundStatus.PENDING, afterExecute.payments().get(1).refunds().get(0).getStatus());
  }

  @Test
  void disputedPayment_rowPresentWithStatusAndReason() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "1000.00");
    UUID paymentId = verify(orgId, admin, "400.00", order.number(), "UNDERPAID");
    // The dispute lifecycle needs an ALLOCATED payment (delivery + invoice); flip the row directly
    // — this slice only asserts the read surfaces the dispute fields.
    dsl.update(PAYMENT)
        .set(PAYMENT.STATUS, com.loai.inventory.repository.generated.enums.PaymentStatus.DISPUTED)
        .set(PAYMENT.DISPUTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .set(PAYMENT.DISPUTE_REASON, "customer denies the transfer")
        .where(PAYMENT.ID.eq(paymentId))
        .execute();

    OrderPayments result = paymentService.listForOrder(orgId, order.id());

    assertEquals(1, result.payments().size());
    Payment p = result.payments().get(0).payment();
    assertEquals(PaymentStatus.DISPUTED, p.getStatus());
    assertEquals("customer denies the transfer", p.getDisputeReason());
    assertNotNull(p.getDisputedAt());
  }

  @Test
  void orderWithNoPayments_emptyData_headerIntact() {
    UUID orgId = createOrg("acme");
    Order order = seedPendingOrder(orgId, "750.00");

    OrderPayments result = paymentService.listForOrder(orgId, order.id());

    assertTrue(result.payments().isEmpty());
    assertEquals(order.id(), result.order().getId());
    assertEquals(OrderStatus.PENDING_PAYMENT, result.order().getStatus());
    assertEquals(0, new BigDecimal("750.00").compareTo(result.order().getGrandTotal()));
  }

  @Test
  void standaloneOrphanRefundPayments_neverAppear_onAnyOrder() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "500.00");
    verify(orgId, admin, "500.00", order.number(), "MATCHED");
    // An orphan dispositioned via refund creates a standalone payment (sales_order_id NULL).
    UUID orphan = verify(orgId, admin, "150.00", null, "ORPHAN");
    txnService.refundOrphan(orgId, orphan, null, null, admin, false);

    OrderPayments result = paymentService.listForOrder(orgId, order.id());

    assertEquals(1, result.payments().size());
    assertEquals(order.id(), result.payments().get(0).payment().getSalesOrderId());
  }

  @Test
  void unknownOrForeignOrder_is404_scopingNotForbidden() {
    UUID orgId = createOrg("acme");
    UUID otherOrg = createOrg("other");
    Order foreign = seedPendingOrder(otherOrg, "100.00");

    assertThrows(
        NotFoundException.class, () -> paymentService.listForOrder(orgId, UUID.randomUUID()));
    // Another org's order is invisible, not forbidden — scoping over the shared schema.
    assertThrows(NotFoundException.class, () -> paymentService.listForOrder(orgId, foreign.id()));
  }

  // helpers

  /**
   * Record-and-verify a credit with a distinct occurred_at; asserts the expected reconciliation and
   * returns the created payment id (null for ORPHAN).
   */
  private UUID verify(UUID orgId, UUID admin, String amount, String orderNumber, String expected) {
    VerifyResult r =
        txnService.verify(
            orgId,
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                "IPN-" + refSeq.getAndIncrement(),
                new BigDecimal(amount),
                "EGP",
                null,
                orderNumber,
                null,
                "paid via InstaPay",
                "proof-blob",
                base.plusMinutes(occurredSeq.getAndIncrement())),
            admin);
    assertEquals(expected, r.reconciliationStatus().name());
    return r.payment() == null ? r.transaction().getId() : r.payment().getId();
  }

  private record Order(UUID id, String number) {}

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  private UUID createUser(String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-" + email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }

  private Order seedPendingOrder(UUID orgId, String grandTotal) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", orderSeq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();
    return new Order(orderId, number);
  }
}
