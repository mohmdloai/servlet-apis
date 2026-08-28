package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
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
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService;
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
 * Integration coverage for orphan resolution ({@code FLOW.md} §"ORPHAN → admin queue"; {@code
 * state-machines.md}: "manual InstaPay will produce orphans on day 1"). An admin records a manual
 * InstaPay arrival with no usable order reference (→ ORPHAN), then later matches it to a chosen
 * order via {@link PaymentTransactionService#resolveOrphan}, reusing the same reconcile / prepaid /
 * SO→PAID path as the automated flow.
 *
 * <p>Drives the service directly against the real jOOQ repositories — no Tomcat. MANAGER-only
 * authorization is enforced in the handler and covered separately.
 */
@Testcontainers
class OrphanResolutionIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PaymentTransactionService service;

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
        "TRUNCATE payment, payment_transaction, sales_order_line, sales_order, customer, product,"
            + " app_user, org RESTART IDENTITY CASCADE");
  }

  // scenarios

  @Test
  void resolveByOrderNumber_exactCover_createsPaymentAndFlipsOrderToPaid() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    VerifyResult result =
        service.resolveOrphan(orgId, txnId, new OrderRef(null, order.number()), admin);

    assertFalse(result.replay());
    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertEquals("MATCHED", txnReconciliation(txnId));

    // order flipped to PAID with the prepayment cached.
    assertEquals("PAID", orderStatus(order.id()));
    assertEquals(0, new BigDecimal("250.00").compareTo(prepaidAmount(order.id())));

    // exactly one payment, RECEIVED + fully unallocated, bound to the orphan transaction.
    assertEquals(1, paymentCountForOrder(order.id()));
    assertNotNull(result.payment());
    assertEquals("RECEIVED", result.payment().getStatus().name());
    assertEquals(txnId, result.payment().getPaymentTransactionId());
    assertEquals(
        0, result.payment().getAmount().compareTo(result.payment().getUnallocatedAmount()));
  }

  @Test
  void resolveBySalesOrderId_exactCover_matches() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    VerifyResult result =
        service.resolveOrphan(orgId, txnId, new OrderRef(order.id(), null), admin);

    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertEquals("PAID", orderStatus(order.id()));
  }

  @Test
  void resolveUnderpaid_rejectedAndTransactionStaysOrphan() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "100.00");

    assertThrows(
        ConflictException.class,
        () -> service.resolveOrphan(orgId, txnId, new OrderRef(null, order.number()), admin));

    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals("PENDING_PAYMENT", orderStatus(order.id()));
    assertEquals(0, paymentCountForOrder(order.id()));
  }

  @Test
  void resolveOverpaid_rejectedAndTransactionStaysOrphan() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "300.00");

    assertThrows(
        ConflictException.class,
        () -> service.resolveOrphan(orgId, txnId, new OrderRef(null, order.number()), admin));

    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals(0, paymentCountForOrder(order.id()));
  }

  @Test
  void resolveAgainstNonPendingOrder_rejectedAndTransactionStaysOrphan() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    dsl.update(SALES_ORDER)
        .set(SALES_ORDER.STATUS, OrderStatus.PAID)
        .where(SALES_ORDER.ID.eq(order.id()))
        .execute();
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    assertThrows(
        ConflictException.class,
        () -> service.resolveOrphan(orgId, txnId, new OrderRef(order.id(), null), admin));

    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals(0, paymentCountForOrder(order.id()));
  }

  @Test
  void resolveAgainstUnknownOrder_notFound() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    assertThrows(
        NotFoundException.class,
        () -> service.resolveOrphan(orgId, txnId, new OrderRef(null, "SO-9999-99999"), admin));

    assertEquals("ORPHAN", txnReconciliation(txnId));
  }

  /**
   * The "orphan with no matching order" case is no longer a dead end: matching still fails cleanly
   * (NotFound, transaction stays ORPHAN and untouched), and the money's exit is now {@link
   * PaymentTransactionService#refundOrphan} — covered end-to-end in {@link OrphanRefundIT}.
   */
  @Test
  void verifiedOrphan_noMatchingOrder_matchFailsCleanly_refundIsTheExit() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00"); // VERIFIED + ORPHAN, no order, no payment

    // Matching against a nonexistent order still fails without side effects.
    assertThrows(
        NotFoundException.class,
        () -> service.resolveOrphan(orgId, txnId, new OrderRef(null, "SO-9999-99999"), admin));
    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals("VERIFIED", txnVerification(txnId));
    assertEquals(0, paymentCountForTxn(txnId));

    // The refund exit promotes the orphan into a standalone payment + PENDING direct refund.
    var refunded = service.refundOrphan(orgId, txnId, null, null, admin, false);
    assertEquals(1, paymentCountForTxn(txnId));
    assertEquals("PENDING", refunded.refund().getStatus().name());
  }

  @Test
  void resolveMissingReference_validationError() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    assertThrows(
        ValidationException.class,
        () -> service.resolveOrphan(orgId, txnId, new OrderRef(null, null), admin));
  }

  @Test
  void resolveUnknownTransaction_notFound() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");

    assertThrows(
        NotFoundException.class,
        () ->
            service.resolveOrphan(orgId, UUID.randomUUID(), new OrderRef(order.id(), null), admin));
  }

  @Test
  void resolveTwice_secondCallReplaysExistingPayment() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    VerifyResult first = service.resolveOrphan(orgId, txnId, new OrderRef(order.id(), null), admin);
    VerifyResult second =
        service.resolveOrphan(orgId, txnId, new OrderRef(order.id(), null), admin);

    assertFalse(first.replay());
    assertTrue(second.replay());
    assertEquals("MATCHED", second.reconciliationStatus().name());
    assertNotNull(second.payment());
    assertNotNull(second.order());
    assertEquals(order.id(), second.order().getId());

    // still exactly one payment despite two resolve calls.
    assertEquals(1, paymentCountForOrder(order.id()));
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

  private int paymentCountForOrder(UUID orderId) {
    return dsl.fetchCount(dsl.selectFrom(PAYMENT).where(PAYMENT.SALES_ORDER_ID.eq(orderId)));
  }

  private int paymentCountForTxn(UUID txnId) {
    return dsl.fetchCount(dsl.selectFrom(PAYMENT).where(PAYMENT.PAYMENT_TRANSACTION_ID.eq(txnId)));
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
