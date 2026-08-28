package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.PaymentProvider;
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
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.tables.records.PaymentRecord;
import com.loai.inventory.repository.generated.tables.records.RefundRecord;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.OrphanRefundResult;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * Integration coverage for the orphan queue's <b>refund exit</b> ({@code state-machines.md} E: an
 * ORPHAN's two resolutions are "link manually" and "refund"; {@code refund.md} §"Orphan
 * transaction": the admin "first creates the Payment … then issues a direct Refund"). A VERIFIED
 * manual-InstaPay arrival that genuinely matches no order (wrong reference, duplicate payment,
 * payment for an expired order) is promoted by {@link PaymentTransactionService#refundOrphan} into
 * a standalone order-less {@code payment} plus a PENDING direct refund, then executed through the
 * ordinary two-step refund lifecycle.
 *
 * <p>Drives the services directly against the real jOOQ repositories — no Tomcat. MANAGER-only
 * authorization is enforced in the handler; the OWNER approval threshold is exercised here via the
 * {@code callerIsOwnerOrAdmin} flag.
 */
@Testcontainers
class OrphanRefundIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PaymentTransactionService service;
  static RefundService refundService;

  private final AtomicInteger orderSeq = new AtomicInteger(1);
  private final AtomicInteger refSeq = new AtomicInteger(1);

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

    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
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
    service =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage(),
            new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.UserRepositoryFactoryImpl());
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

  // scenarios

  @Test
  void refundOrphan_promotesToStandalonePaymentAndPendingRefund() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    OrphanRefundResult result = service.refundOrphan(orgId, txnId, null, null, admin, false);

    assertFalse(result.replay());

    // Standalone payment: order-less, RECEIVED, fully unallocated, 1:1 with the orphan txn.
    PaymentRecord payment = paymentForTxn(txnId);
    assertNotNull(payment);
    assertNull(payment.getSalesOrderId());
    assertEquals("RECEIVED", payment.getStatus().getLiteral().toUpperCase());
    assertEquals(0, new BigDecimal("250.00").compareTo(payment.getAmount()));
    assertEquals(0, new BigDecimal("250.00").compareTo(payment.getUnallocatedAmount()));
    assertEquals(result.payment().getId(), payment.getId());

    // PENDING direct refund for the full amount, method defaulted to the txn's own provider.
    RefundRecord refund = refundRow(result.refund().getId());
    assertEquals("PENDING", refund.getStatus().getLiteral());
    assertEquals(0, new BigDecimal("250.00").compareTo(refund.getAmount()));
    assertEquals(payment.getId(), refund.getPaymentId());
    assertNull(refund.getCreditNoteId());
    assertEquals("instapay_manual", refund.getMethod().getLiteral());

    // No money moved yet: no DEBIT transaction exists until the refund executes.
    assertEquals(0, debitTxnCount(orgId));

    // Reconciliation history is preserved — the payment is the disposition marker.
    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals("VERIFIED", txnVerification(txnId));
  }

  @Test
  void executeAfterRefundOrphan_movesMoneyOutThroughStandardTwoStepLifecycle() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00");
    OrphanRefundResult created = service.refundOrphan(orgId, txnId, null, null, admin, false);

    // Admin performs the real reverse transfer, then records it.
    refundService.execute(orgId, created.refund().getId(), "IP-RETURN-778899", admin);

    RefundRecord refund = refundRow(created.refund().getId());
    assertEquals("EXECUTED", refund.getStatus().getLiteral());
    assertNotNull(refund.getPaymentTransactionId());

    // The standalone payment is drained and terminal.
    PaymentRecord payment = paymentForTxn(txnId);
    assertEquals(0, BigDecimal.ZERO.compareTo(payment.getUnallocatedAmount()));
    assertEquals("REFUNDED", payment.getStatus().getLiteral().toUpperCase());

    // Money-out is on the books: one VERIFIED DEBIT transaction for the full amount.
    assertEquals(1, debitTxnCount(orgId));
  }

  @Test
  void refundOrphan_replay_returnsSameRefundAndCreatesNothing() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    OrphanRefundResult first = service.refundOrphan(orgId, txnId, null, null, admin, false);
    OrphanRefundResult second = service.refundOrphan(orgId, txnId, null, null, admin, false);

    assertFalse(first.replay());
    assertTrue(second.replay());
    assertEquals(first.refund().getId(), second.refund().getId());
    assertEquals(1, paymentCountForTxn(txnId));
    assertEquals(1, refundCountForPayment(first.payment().getId()));
  }

  @Test
  void refundOrphan_explicitMethod_overridesProviderDefault() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    OrphanRefundResult result =
        service.refundOrphan(
            orgId, txnId, PaymentProvider.CASH, "returned at counter", admin, false);

    RefundRecord refund = refundRow(result.refund().getId());
    assertEquals("cash", refund.getMethod().getLiteral());
    assertEquals("returned at counter", refund.getNotes());
  }

  @Test
  void refundOrphan_matchedTransaction_rejected() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "250.00");
    VerifyResult resolved =
        service.resolveOrphan(orgId, txnId, new OrderRef(order.id(), null), admin);
    assertEquals("MATCHED", resolved.reconciliationStatus().name());

    // Money attached to an order is refunded via cancellation / credit note, never this path.
    assertThrows(
        ConflictException.class,
        () -> service.refundOrphan(orgId, txnId, null, null, admin, false));
    assertEquals(0, refundCountForPayment(resolved.payment().getId()));
  }

  @Test
  void refundOrphan_unknownTransaction_notFound() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");

    assertThrows(
        NotFoundException.class,
        () -> service.refundOrphan(orgId, UUID.randomUUID(), null, null, admin, false));
  }

  /**
   * The OWNER approval threshold (org default 500.00, V33) gates this path exactly like any other
   * direct refund — and a denial rolls back the whole promotion: no standalone payment may survive
   * without its refund obligation.
   */
  @Test
  void refundOrphan_aboveThreshold_requiresOwner_andDenialRollsBackAtomically() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "600.00");

    assertThrows(
        AuthorizationException.class,
        () -> service.refundOrphan(orgId, txnId, null, null, admin, false));

    // Atomic: the payment insert was rolled back with the refused refund.
    assertEquals(0, paymentCountForTxn(txnId));
    assertEquals("ORPHAN", txnReconciliation(txnId));

    // The OWNER (or system ADMIN) can approve the same disposition.
    OrphanRefundResult result = service.refundOrphan(orgId, txnId, null, null, admin, true);
    assertFalse(result.replay());
    assertEquals(1, paymentCountForTxn(txnId));
    assertEquals(0, new BigDecimal("600.00").compareTo(result.refund().getAmount()));
  }

  /** A cancelled PENDING refund re-opens: the money is still held, so the exit must stay usable. */
  @Test
  void refundOrphan_afterCancelledRefund_opensFreshPendingOnSamePayment() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00");
    OrphanRefundResult first = service.refundOrphan(orgId, txnId, null, null, admin, false);

    refundService.cancel(orgId, first.refund().getId(), "customer unreachable, retry later");

    OrphanRefundResult second = service.refundOrphan(orgId, txnId, null, null, admin, false);

    assertFalse(second.replay());
    assertNotEquals(first.refund().getId(), second.refund().getId());
    assertEquals("PENDING", second.refund().getStatus().name());
    // Same standalone payment underneath — no duplicate promotion.
    assertEquals(first.payment().getId(), second.payment().getId());
    assertEquals(1, paymentCountForTxn(txnId));
  }

  // helpers

  /** Record a VERIFIED manual-InstaPay arrival with no order reference → lands as ORPHAN. */
  private UUID seedOrphan(UUID orgId, UUID admin, String amount) {
    VerifyResult r =
        service.verify(orgId, cmd(PaymentProvider.INSTAPAY_MANUAL, amount, null), admin);
    assertEquals("ORPHAN", r.reconciliationStatus().name());
    return r.transaction().getId();
  }

  private VerifyCommand cmd(PaymentProvider provider, String amount, String orderNumber) {
    return new VerifyCommand(
        provider,
        "IPN-" + refSeq.getAndIncrement(),
        new BigDecimal(amount),
        "EGP",
        null,
        orderNumber,
        null,
        "paid via InstaPay",
        "proof-blob",
        null);
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
        .set(SALES_ORDER.STATUS, OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();
    return new Order(orderId, number);
  }

  private PaymentRecord paymentForTxn(UUID txnId) {
    return dsl.selectFrom(PAYMENT).where(PAYMENT.PAYMENT_TRANSACTION_ID.eq(txnId)).fetchOne();
  }

  private int paymentCountForTxn(UUID txnId) {
    return dsl.fetchCount(dsl.selectFrom(PAYMENT).where(PAYMENT.PAYMENT_TRANSACTION_ID.eq(txnId)));
  }

  private RefundRecord refundRow(UUID refundId) {
    return dsl.selectFrom(REFUND).where(REFUND.ID.eq(refundId)).fetchOne();
  }

  private int refundCountForPayment(UUID paymentId) {
    return dsl.fetchCount(dsl.selectFrom(REFUND).where(REFUND.PAYMENT_ID.eq(paymentId)));
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

  private String txnVerification(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
        .getLiteral();
  }

  private String txnReconciliation(UUID txnId) {
    var v =
        dsl.select(PAYMENT_TRANSACTION.RECONCILIATION_STATUS)
            .from(PAYMENT_TRANSACTION)
            .where(PAYMENT_TRANSACTION.ID.eq(txnId))
            .fetchOne(PAYMENT_TRANSACTION.RECONCILIATION_STATUS);
    return v == null ? null : v.getLiteral();
  }
}
