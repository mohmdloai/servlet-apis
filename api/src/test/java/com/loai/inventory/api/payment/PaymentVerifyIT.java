package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
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
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * Integration coverage for the accept-online-payment slice ({@code
 * stories/accept_online_payment.md}). Boots one PostgreSQL container, runs all Flyway migrations,
 * and wires {@link PaymentTransactionService} + {@link PaymentService} against the real jOOQ
 * repository factories — no Tomcat/Redis/JWT, so each reconciliation scenario is fast and
 * deterministic.
 *
 * <p>Authorization (MANAGER-only) is enforced in the handler via {@code AuthzHelper} and is not
 * exercised here — this harness drives the service directly.
 */
@Testcontainers
class PaymentVerifyIT {

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
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
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
        "TRUNCATE payment, payment_transaction, sales_order_line, sales_order, customer, product,"
            + " app_user, org RESTART IDENTITY CASCADE");
  }

  // scenarios

  @Test
  void matched_createsPaymentAndFlipsOrderToPaid() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");

    VerifyResult result =
        service.verify(
            orgId, cmd(PaymentProvider.INSTAPAY_MANUAL, "250.00", order.number()), admin);

    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertEquals("PAID", orderStatus(order.id()));
    assertEquals(0, new BigDecimal("250.00").compareTo(prepaidAmount(order.id())));

    // exactly one payment, RECEIVED, fully unallocated.
    assertEquals(1, paymentCountForOrder(order.id()));
    assertNotNull(result.payment());
    assertEquals("RECEIVED", result.payment().getStatus().name());
    assertEquals(
        0, result.payment().getAmount().compareTo(result.payment().getUnallocatedAmount()));
    assertEquals(0, new BigDecimal("250.00").compareTo(result.payment().getAmount()));

    // transaction VERIFIED + MATCHED, verified_by stamped.
    assertEquals("VERIFIED", txnVerification(result.transaction().getId()));
    assertEquals("MATCHED", txnReconciliation(result.transaction().getId()));
    assertEquals(admin, result.transaction().getVerifiedBy());
    assertTrue(result.replay() == false);
  }

  @Test
  void underpaid_createsPartialPaymentAndOrderStaysPending() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");

    VerifyResult result =
        service.verify(
            orgId, cmd(PaymentProvider.INSTAPAY_MANUAL, "100.00", order.number()), admin);

    // UNDERPAID still records a refundable prepayment (state-machines.md E / payment.md): the
    // partial Payment exists, prepaid_amount accumulates, but the order stays PENDING_PAYMENT.
    assertEquals("UNDERPAID", result.reconciliationStatus().name());
    assertEquals("PENDING_PAYMENT", orderStatus(order.id()));
    assertEquals(0, new BigDecimal("100.00").compareTo(prepaidAmount(order.id())));

    assertEquals(1, paymentCountForOrder(order.id()));
    assertNotNull(result.payment());
    assertEquals("RECEIVED", result.payment().getStatus().name());
    assertEquals(
        0, result.payment().getAmount().compareTo(result.payment().getUnallocatedAmount()));
    assertEquals(0, new BigDecimal("100.00").compareTo(result.payment().getAmount()));
  }

  @Test
  void overpaid_createsPaymentAndMarksOrderPaid() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");

    VerifyResult result =
        service.verify(
            orgId, cmd(PaymentProvider.INSTAPAY_MANUAL, "300.00", order.number()), admin);

    // OVERPAID still recognizes the money (state-machines.md E / payment.md §Overpaid online):
    // the order is fully covered so it flips to PAID; the 50.00 excess stays on the Payment as
    // unallocated_amount for a later direct refund.
    assertEquals("OVERPAID", result.reconciliationStatus().name());
    assertEquals("OVERPAID", txnReconciliation(result.transaction().getId()));
    assertEquals("PAID", orderStatus(order.id()));
    assertEquals(0, new BigDecimal("300.00").compareTo(prepaidAmount(order.id())));

    assertEquals(1, paymentCountForOrder(order.id()));
    assertNotNull(result.payment());
    assertEquals("RECEIVED", result.payment().getStatus().name());
    assertEquals(0, new BigDecimal("300.00").compareTo(result.payment().getAmount()));
    assertEquals(
        0, result.payment().getAmount().compareTo(result.payment().getUnallocatedAmount()));
  }

  @Test
  void orphan_unknownOrderNumber_createsNoPayment() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");

    VerifyResult result =
        service.verify(
            orgId, cmd(PaymentProvider.INSTAPAY_MANUAL, "250.00", "SO-9999-99999"), admin);

    assertEquals("ORPHAN", result.reconciliationStatus().name());
    assertEquals("ORPHAN", txnReconciliation(result.transaction().getId()));
    assertNull(result.payment());
  }

  @Test
  void idempotentReplay_sameProviderRef_oneTxnOnePaymentSameOutcome() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    VerifyCommand command = cmd(PaymentProvider.INSTAPAY_MANUAL, "250.00", order.number());

    VerifyResult first = service.verify(orgId, command, admin);
    VerifyResult second = service.verify(orgId, command, admin);

    assertTrue(first.replay() == false);
    assertTrue(second.replay());
    assertEquals(first.transaction().getId(), second.transaction().getId());
    assertEquals("MATCHED", second.reconciliationStatus().name());

    // The replay response carries the same shape as the first call — payment + order both present.
    assertNotNull(second.payment());
    assertNotNull(second.order());
    assertEquals("PAID", second.order().getStatus().name());
    assertEquals(order.id(), second.order().getId());

    // exactly one transaction and one payment despite two calls.
    assertEquals(1, txnCount(orgId));
    assertEquals(1, paymentCountForOrder(order.id()));
    assertEquals("PAID", orderStatus(order.id()));
  }

  /**
   * The headline concurrency property: a payment verify and an order expiry cannot both win. The
   * verifier's reconcile takes {@code SELECT … FOR UPDATE} on the order, so it serializes behind an
   * in-flight expiry — when expiry commits first, the verifier observes the committed {@code
   * EXPIRED} status and yields ORPHAN instead of flipping an expired order to PAID.
   */
  @Test
  void concurrentExpiry_lockSerializes_orderEndsExpiredNotPaid() throws Exception {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");

    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch proceedToCommit = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // Holder: lock the order FOR UPDATE, mark it EXPIRED, hold the lock (uncommitted) until told.
      Future<?> holder =
          pool.submit(
              () ->
                  dsl.transaction(
                      cfg -> {
                        DSLContext tx = DSL.using(cfg);
                        tx.selectFrom(SALES_ORDER)
                            .where(SALES_ORDER.ID.eq(order.id()))
                            .forUpdate()
                            .fetch();
                        tx.update(SALES_ORDER)
                            .set(SALES_ORDER.STATUS, OrderStatus.EXPIRED)
                            .set(SALES_ORDER.EXPIRED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                            .where(SALES_ORDER.ID.eq(order.id()))
                            .execute();
                        lockHeld.countDown();
                        proceedToCommit.await(); // hold the row lock, EXPIRED still uncommitted
                      }));

      lockHeld.await(5, TimeUnit.SECONDS);

      // Verifier races: reconcile must block on the held row lock and cannot complete yet.
      Future<VerifyResult> verifier =
          pool.submit(
              () ->
                  service.verify(
                      orgId,
                      cmd(PaymentProvider.INSTAPAY_MANUAL, "250.00", order.number()),
                      admin));
      assertThrows(TimeoutException.class, () -> verifier.get(500, TimeUnit.MILLISECONDS));

      proceedToCommit.countDown(); // holder commits EXPIRED, releasing the lock
      holder.get(5, TimeUnit.SECONDS);
      VerifyResult result = verifier.get(5, TimeUnit.SECONDS);

      // The verifier observed the committed EXPIRED status → ORPHAN; no payment; order stays
      // EXPIRED.
      assertEquals("ORPHAN", result.reconciliationStatus().name());
      assertEquals("EXPIRED", orderStatus(order.id()));
      assertEquals(0, paymentCountForOrder(order.id()));
    } finally {
      pool.shutdownNow();
    }
  }

  // helpers

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

  private int txnCount(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION).where(PAYMENT_TRANSACTION.ORG_ID.eq(orgId)));
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
