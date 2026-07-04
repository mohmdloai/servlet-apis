package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.repository.PaymentTransactionRepository.ListFilter;
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
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.TransactionDetail;
import com.loai.inventory.service.PaymentTransactionService.TransactionPage;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
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
 * Integration coverage for the payment-transaction reads ({@code
 * stories/list_payment_transactions.md}): the filtered worklist list (the open orphan queue is the
 * composition {@code reconciliation_status=ORPHAN + has_payment=false} — the payment-exists
 * exclusion from {@code transaction.md} §Operational queries) and the {@code /{id}} detail with its
 * disposition payment/order.
 *
 * <p>Drives the services directly against the real jOOQ repositories — no Tomcat. VIEWER
 * authorization and query-param parsing (400s) are enforced in the handler, as everywhere.
 */
@Testcontainers
class PaymentTransactionReadIT {

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

    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    refundService =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
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
        "TRUNCATE refund, payment, payment_transaction, sales_order_line, sales_order, customer,"
            + " product, app_user, org RESTART IDENTITY CASCADE");
  }

  /** Every transaction the full seed writes, keyed for assertions. */
  private record Seeded(
      UUID orgId,
      UUID admin,
      UUID claim,
      UUID matched,
      UUID openOrphan,
      UUID resolvedOrphan,
      UUID refundedOrphan,
      UUID underpaid,
      UUID overpaid) {}

  /**
   * One org exercising every reachable state: an UNVERIFIED claim, a MATCHED credit, an open
   * ORPHAN, an orphan dispositioned via <b>resolve</b> (reconciliation flips ORPHAN → MATCHED), an
   * orphan dispositioned via <b>refund</b> (stays ORPHAN; its payment is the disposition marker),
   * UNDERPAID and OVERPAID credits (both create payments), and — via executing the orphan refund —
   * a VERIFIED DEBIT. 8 transactions total.
   */
  private Seeded seedAll() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");

    UUID claim = seedUnverifiedClaim(orgId, "100.00");

    Order matchedOrder = seedPendingOrder(orgId, "500.00");
    UUID matched = verify(orgId, admin, "500.00", matchedOrder.number(), "MATCHED");

    UUID openOrphan = verify(orgId, admin, "250.00", null, "ORPHAN");

    UUID resolvedOrphan = verify(orgId, admin, "300.00", null, "ORPHAN");
    Order resolveTarget = seedPendingOrder(orgId, "300.00");
    service.resolveOrphan(orgId, resolvedOrphan, new OrderRef(null, resolveTarget.number()), admin);

    UUID refundedOrphan = verify(orgId, admin, "150.00", null, "ORPHAN");
    UUID refundId =
        service.refundOrphan(orgId, refundedOrphan, null, null, admin, false).refund().getId();
    // Executing writes the VERIFIED DEBIT money-out transaction.
    refundService.execute(orgId, refundId, "IP-RETURN-" + refSeq.getAndIncrement(), admin);

    Order underpaidOrder = seedPendingOrder(orgId, "1000.00");
    UUID underpaid = verify(orgId, admin, "400.00", underpaidOrder.number(), "UNDERPAID");

    Order overpaidOrder = seedPendingOrder(orgId, "500.00");
    UUID overpaid = verify(orgId, admin, "600.00", overpaidOrder.number(), "OVERPAID");

    return new Seeded(
        orgId,
        admin,
        claim,
        matched,
        openOrphan,
        resolvedOrphan,
        refundedOrphan,
        underpaid,
        overpaid);
  }

  // ───────────────────────────── list: the orphan queue ─────────────────────────────

  @Test
  void openOrphanQueue_isOrphanPlusNoPayment_excludingBothDispositions() {
    Seeded s = seedAll();

    TransactionPage page =
        service.list(s.orgId(), filter(null, PaymentReconciliationStatus.ORPHAN, false), 0, 20);

    assertEquals(1, page.total());
    assertEquals(List.of(s.openOrphan()), ids(page));
  }

  @Test
  void orphanHistory_isOrphanPlusPayment_theRefundDisposition() {
    Seeded s = seedAll();

    TransactionPage page =
        service.list(s.orgId(), filter(null, PaymentReconciliationStatus.ORPHAN, true), 0, 20);

    // Only the refund exit keeps reconciliation=ORPHAN; resolve flips it to MATCHED.
    assertEquals(List.of(s.refundedOrphan()), ids(page));
  }

  @Test
  void orphanLiteral_returnsOpenAndRefundDispositioned_noHiddenExpansion() {
    Seeded s = seedAll();

    TransactionPage page =
        service.list(s.orgId(), filter(null, PaymentReconciliationStatus.ORPHAN, null), 0, 20);

    assertEquals(2, page.total());
    assertTrue(ids(page).containsAll(List.of(s.openOrphan(), s.refundedOrphan())));
  }

  @Test
  void afterRefundDisposition_queueShrinks_literalOrphanListDoesNot() {
    Seeded s = seedAll();
    ListFilter queue = filter(null, PaymentReconciliationStatus.ORPHAN, false);
    ListFilter literal = filter(null, PaymentReconciliationStatus.ORPHAN, null);
    assertEquals(1, service.list(s.orgId(), queue, 0, 20).total());
    assertEquals(2, service.list(s.orgId(), literal, 0, 20).total());

    service.refundOrphan(s.orgId(), s.openOrphan(), null, null, s.admin(), false);

    assertEquals(0, service.list(s.orgId(), queue, 0, 20).total());
    assertEquals(2, service.list(s.orgId(), literal, 0, 20).total());
    // The detail now shows the standalone disposition payment.
    TransactionDetail detail = service.get(s.orgId(), s.openOrphan());
    assertNotNull(detail.payment());
    assertNull(detail.payment().getSalesOrderId());
  }

  // ───────────────────────────── list: the other filters ─────────────────────────────

  @Test
  void statusFilters_matchColumnsLiterally() {
    Seeded s = seedAll();

    assertEquals(
        List.of(s.claim()),
        ids(
            service.list(
                s.orgId(), filter(PaymentVerificationStatus.UNVERIFIED, null, null), 0, 20)));
    assertEquals(
        List.of(s.underpaid()),
        ids(
            service.list(
                s.orgId(), filter(null, PaymentReconciliationStatus.UNDERPAID, null), 0, 20)));
    assertEquals(
        List.of(s.overpaid()),
        ids(
            service.list(
                s.orgId(), filter(null, PaymentReconciliationStatus.OVERPAID, null), 0, 20)));

    // MATCHED = the straight match plus the resolve-dispositioned orphan (ORPHAN → MATCHED).
    List<UUID> matched =
        ids(
            service.list(
                s.orgId(), filter(null, PaymentReconciliationStatus.MATCHED, null), 0, 20));
    assertEquals(2, matched.size());
    assertTrue(matched.containsAll(List.of(s.matched(), s.resolvedOrphan())));
  }

  @Test
  void hasPaymentAlone_returnsEveryTransactionWithAPayment_acrossStatuses() {
    Seeded s = seedAll();

    List<UUID> withPayment = ids(service.list(s.orgId(), filter(null, null, true), 0, 20));

    // MATCHED, resolved orphan, refunded orphan, UNDERPAID, OVERPAID — the DEBIT, the claim and
    // the open orphan have none.
    assertEquals(5, withPayment.size());
    assertTrue(
        withPayment.containsAll(
            List.of(
                s.matched(), s.resolvedOrphan(), s.refundedOrphan(), s.underpaid(), s.overpaid())));
  }

  // ───────────────────────────── list: ordering + paging ─────────────────────────────

  @Test
  void unfilteredLedger_returnsEverything_newestRecordedFirst() {
    Seeded s = seedAll();

    TransactionPage page = service.list(s.orgId(), filter(null, null, null), 0, 20);

    // 7 credits (incl. the UNVERIFIED claim) + the executed refund's DEBIT.
    assertEquals(8, page.total());
    List<PaymentTransaction> items = page.items();
    for (int i = 1; i < items.size(); i++) {
      assertTrue(
          !items.get(i - 1).getRecordedAt().isBefore(items.get(i).getRecordedAt()),
          "ledger must be recorded_at DESC");
    }
  }

  @Test
  void filteredQueue_isOldestFirstByOccurredAt() {
    Seeded s = seedAll();

    List<PaymentTransaction> items =
        service.list(s.orgId(), filter(null, null, true), 0, 20).items();

    assertEquals(5, items.size());
    for (int i = 1; i < items.size(); i++) {
      assertTrue(
          !items.get(i - 1).getOccurredAt().isAfter(items.get(i).getOccurredAt()),
          "queue views must be occurred_at ASC");
    }
  }

  @Test
  void pagination_tilesTheFilteredSetWithoutOverlap() {
    Seeded s = seedAll();
    ListFilter withPayment = filter(null, null, true);

    TransactionPage p0 = service.list(s.orgId(), withPayment, 0, 2);
    TransactionPage p1 = service.list(s.orgId(), withPayment, 1, 2);
    TransactionPage p2 = service.list(s.orgId(), withPayment, 2, 2);

    assertEquals(5, p0.total());
    assertEquals(2, p0.items().size());
    assertEquals(2, p1.items().size());
    assertEquals(1, p2.items().size());
    List<UUID> all = ids(p0);
    all.addAll(ids(p1));
    all.addAll(ids(p2));
    assertEquals(5, all.stream().distinct().count());
  }

  @Test
  void pageAndSize_areClampedNotErrors() {
    Seeded s = seedAll();

    // size floors at 1, negative page floors at 0 — a sloppy client gets data, not a 500.
    TransactionPage page = service.list(s.orgId(), filter(null, null, null), -3, 0);
    assertEquals(1, page.items().size());
    assertEquals(8, page.total());
  }

  @Test
  void listing_isOrgScoped() {
    Seeded s = seedAll();
    UUID otherOrg = createOrg("other");
    UUID otherAdmin = createUser("admin@other.test");
    UUID foreignOrphan = verify(otherOrg, otherAdmin, "999.00", null, "ORPHAN");

    List<UUID> mine =
        ids(service.list(s.orgId(), filter(null, PaymentReconciliationStatus.ORPHAN, null), 0, 20));

    assertTrue(mine.stream().noneMatch(foreignOrphan::equals));
    assertEquals(
        List.of(foreignOrphan),
        ids(
            service.list(
                otherOrg, filter(null, PaymentReconciliationStatus.ORPHAN, false), 0, 20)));
  }

  // ───────────────────────────── detail ─────────────────────────────

  @Test
  void detail_openOrphan_hasNoMoneyContext() {
    Seeded s = seedAll();

    TransactionDetail detail = service.get(s.orgId(), s.openOrphan());

    assertEquals(s.openOrphan(), detail.transaction().getId());
    assertNull(detail.payment());
    assertNull(detail.order());
  }

  @Test
  void detail_resolveDispositioned_showsOrderLinkedPaymentAndOrder() {
    Seeded s = seedAll();

    TransactionDetail detail = service.get(s.orgId(), s.resolvedOrphan());

    assertNotNull(detail.payment());
    assertNotNull(detail.payment().getSalesOrderId());
    assertNotNull(detail.order());
    assertEquals(detail.payment().getSalesOrderId(), detail.order().getId());
  }

  @Test
  void detail_refundDispositioned_showsStandalonePaymentOnly() {
    Seeded s = seedAll();

    TransactionDetail detail = service.get(s.orgId(), s.refundedOrphan());

    assertNotNull(detail.payment());
    assertNull(detail.payment().getSalesOrderId());
    assertNull(detail.order());
  }

  @Test
  void detail_matchedCredit_showsPaymentAndOrder_sameShapeAsVerifyTime() {
    Seeded s = seedAll();

    TransactionDetail detail = service.get(s.orgId(), s.matched());

    assertNotNull(detail.payment());
    assertNotNull(detail.order());
    assertEquals(0, new BigDecimal("500.00").compareTo(detail.payment().getAmount()));
  }

  @Test
  void detail_unknownOrForeignId_is404() {
    Seeded s = seedAll();
    UUID otherOrg = createOrg("other");
    UUID otherAdmin = createUser("admin@other.test");
    UUID foreign = verify(otherOrg, otherAdmin, "999.00", null, "ORPHAN");

    assertThrows(NotFoundException.class, () -> service.get(s.orgId(), UUID.randomUUID()));
    // Another org's transaction is invisible, not forbidden — scoping over the shared schema.
    assertThrows(NotFoundException.class, () -> service.get(s.orgId(), foreign));
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private static ListFilter filter(
      PaymentVerificationStatus v, PaymentReconciliationStatus r, Boolean hasPayment) {
    return new ListFilter(v, r, hasPayment);
  }

  private static List<UUID> ids(TransactionPage page) {
    return page.items().stream()
        .map(PaymentTransaction::getId)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * Record-and-verify a credit with a distinct occurred_at; asserts the expected reconciliation.
   */
  private UUID verify(UUID orgId, UUID admin, String amount, String orderNumber, String expected) {
    VerifyResult r =
        service.verify(
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
    return r.transaction().getId();
  }

  /** An UNVERIFIED customer claim — no service path leaves this state, so seed the row directly. */
  private UUID seedUnverifiedClaim(UUID orgId, String amount) {
    UUID id = UUID.randomUUID();
    OffsetDateTime occurred = base.plusMinutes(occurredSeq.getAndIncrement());
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, id)
        .set(PAYMENT_TRANSACTION.ORG_ID, orgId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.valueOf(
                PaymentProvider.INSTAPAY_MANUAL.dbLiteral()))
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + refSeq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.CURRENCY, "EGP")
        .set(
            PAYMENT_TRANSACTION.VERIFICATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.UNVERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, occurred)
        .set(PAYMENT_TRANSACTION.RECORDED_AT, occurred)
        .execute();
    return id;
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
}
