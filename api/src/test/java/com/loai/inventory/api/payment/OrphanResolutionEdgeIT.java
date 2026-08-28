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
import static org.junit.jupiter.api.Assertions.fail;

import com.loai.inventory.api.support.TestWiring;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Edge-case integration coverage for orphan resolution ({@link
 * PaymentTransactionService#resolveOrphan}) that {@code OrphanResolutionIT} does not exercise. This
 * file deliberately probes the claims the happy-path suite leaves implicit:
 *
 * <ul>
 *   <li><b>cross-org isolation</b> — an order or transaction in another org is invisible (the
 *       {@code orgId}-scoped {@code findByIdForUpdate} lookups never leak across tenants);
 *   <li><b>the DEBIT guard</b> — only CREDIT transactions can be matched to an order;
 *   <li><b>replay re-binding</b> — a replayed resolve returns the order the persisted payment is
 *       actually attached to, ignoring a new (different) request ref;
 *   <li><b>concurrency</b> — two simultaneous resolves of the same orphan produce exactly one
 *       payment (the {@code FOR UPDATE} row lock prevents a double-insert).
 * </ul>
 *
 * <p>Mirrors {@code OrphanResolutionIT}'s infra setup verbatim: same container, same service
 * wiring, same helpers. Drives the service directly against the real jOOQ repositories — no Tomcat.
 */
@Testcontainers
class OrphanResolutionEdgeIT {

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
  void crossOrg_orderInDifferentOrg_notFoundAndStaysOrphan() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("globex");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgA, admin, "250.00");
    Order orderB = seedPendingOrder(orgB, "250.00");

    // orgA cannot see orgB's order: reconcile finds nothing → ORPHAN → NotFound, nothing persisted.
    assertThrows(
        NotFoundException.class,
        () -> service.resolveOrphan(orgA, txnId, new OrderRef(orderB.id(), null), admin));

    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals("PENDING_PAYMENT", orderStatus(orderB.id()));
    assertEquals(0, paymentCountForOrder(orderB.id()));
  }

  @Test
  void crossOrg_transactionFromAnotherOrg_notFound() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("globex");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedOrphan(orgA, admin, "250.00");
    Order orderB = seedPendingOrder(orgB, "250.00");

    // Resolving orgA's txn under orgB's scope: the txn lookup is org-scoped, so it is not found.
    assertThrows(
        NotFoundException.class,
        () -> service.resolveOrphan(orgB, txnId, new OrderRef(orderB.id(), null), admin));

    // Queried globally (no org filter), the txn is untouched.
    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals("PENDING_PAYMENT", orderStatus(orderB.id()));
    assertEquals(0, paymentCountForOrder(orderB.id()));
  }

  @Test
  void debitTransaction_rejectedAsValidationError() {
    UUID orgA = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID txnId = seedDebitOrphan(orgA, admin, "250.00");
    Order order = seedPendingOrder(orgA, "250.00");

    // The DEBIT guard fires before reconcile: only CREDIT transactions can be matched to an order.
    assertThrows(
        ValidationException.class,
        () -> service.resolveOrphan(orgA, txnId, new OrderRef(order.id(), null), admin));

    assertEquals("ORPHAN", txnReconciliation(txnId));
    assertEquals(0, paymentCountForOrder(order.id()));
  }

  @Test
  void replayReturnsActuallyAttachedOrder_notRequestRef() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order orderA = seedPendingOrder(orgId, "250.00");
    Order orderB = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    // First resolve binds the payment to order A.
    VerifyResult first =
        service.resolveOrphan(orgId, txnId, new OrderRef(orderA.id(), null), admin);
    assertFalse(first.replay());
    assertEquals("MATCHED", first.reconciliationStatus().name());
    assertEquals(orderA.id(), first.order().getId());

    // Second resolve names a DIFFERENT order (B). Replay must re-bind to the persisted payment's
    // order (A), NOT the new request ref (B).
    VerifyResult second =
        service.resolveOrphan(orgId, txnId, new OrderRef(orderB.id(), null), admin);
    assertTrue(second.replay());
    assertNotNull(second.order());
    assertEquals(orderA.id(), second.order().getId());

    // Order B was never touched; A holds the single payment.
    assertEquals("PENDING_PAYMENT", orderStatus(orderB.id()));
    assertEquals(0, paymentCountForOrder(orderB.id()));
    assertEquals("PAID", orderStatus(orderA.id()));
    assertEquals(1, paymentCountForOrder(orderA.id()));
  }

  @Test
  void concurrentResolve_onlyOneMatches_singlePayment() throws Exception {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    Order order = seedPendingOrder(orgId, "250.00");
    UUID txnId = seedOrphan(orgId, admin, "250.00");

    int threads = 2;
    CyclicBarrier startLine = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    List<VerifyResult> results = new CopyOnWriteArrayList<>();
    List<Throwable> failures = new CopyOnWriteArrayList<>();

    Callable<Void> task =
        () -> {
          try {
            startLine.await(10, TimeUnit.SECONDS); // align both threads before the race
            VerifyResult r =
                service.resolveOrphan(orgId, txnId, new OrderRef(order.id(), null), admin);
            results.add(r);
          } catch (Throwable t) {
            failures.add(t);
          }
          return null;
        };

    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(pool.submit(task));
    }
    for (Future<Void> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    // A thrown exception on the losing thread is acceptable; both threads throwing is not.
    if (failures.size() == threads) {
      fail("both concurrent resolves failed: " + failures);
    }

    // The invariant holds regardless of interleaving or which thread won.
    assertEquals(1, paymentCountForOrder(order.id()), "exactly one payment must exist");
    assertEquals("PAID", orderStatus(order.id()));
    assertEquals("MATCHED", txnReconciliation(txnId));

    // At most one call may have done the real insert (replay()==false); the other replayed or
    // threw.
    long realInserts = results.stream().filter(r -> !r.replay()).count();
    assertTrue(realInserts <= 1, "at most one resolve may be a non-replay insert");
  }

  // helpers

  /** Record a VERIFIED manual-InstaPay arrival with no order reference → lands as ORPHAN. */
  private UUID seedOrphan(UUID orgId, UUID admin, String amount) {
    VerifyResult r =
        service.verify(orgId, cmd(PaymentProvider.INSTAPAY_MANUAL, amount, null), admin);
    assertEquals("ORPHAN", r.reconciliationStatus().name());
    return r.transaction().getId();
  }

  /**
   * Insert a VERIFIED DEBIT transaction in ORPHAN reconciliation directly via jOOQ (the service has
   * no path that creates DEBIT rows). Fills every NOT NULL column; nullable columns are omitted.
   */
  private UUID seedDebitOrphan(UUID orgId, UUID admin, String amount) {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, id)
        .set(PAYMENT_TRANSACTION.ORG_ID, orgId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "DEBIT-" + UUID.randomUUID())
        .set(
            PAYMENT_TRANSACTION.DIRECTION,
            com.loai.inventory.repository.generated.enums.PaymentDirection.DEBIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.CURRENCY, "EGP")
        .set(
            PAYMENT_TRANSACTION.VERIFICATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.VERIFIED_BY, admin)
        .set(PAYMENT_TRANSACTION.VERIFIED_AT, now)
        .set(
            PAYMENT_TRANSACTION.RECONCILIATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus.ORPHAN)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now)
        .set(PAYMENT_TRANSACTION.RECORDED_AT, now)
        .execute();
    return id;
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

  private int paymentCountForOrder(UUID orderId) {
    return dsl.fetchCount(dsl.selectFrom(PAYMENT).where(PAYMENT.SALES_ORDER_ID.eq(orderId)));
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
