package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.repository.PaymentTransactionRepository.ListFilter;
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
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.TransactionPage;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * The ledger dimensions of {@code GET /payment-transactions} ({@code
 * stories/transaction_filters.md}): the {@code q} legs (reference fragment, linked order number,
 * that order's customer by folded name or phone, the claimant), the {@code occurred_at} window, the
 * amount band, the explicit sort against the queue-vs-ledger rule, the money summary, and the
 * matched order + customer each row now carries. Drives the service against the real jOOQ
 * repositories — no Tomcat; parsing and 400s live in {@code PaymentTransactionHandlerAuthTest}.
 */
@Testcontainers
class TransactionFiltersIT {

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
  private final AtomicInteger occurredSeq = new AtomicInteger(1);
  // Every seeded row gets its own minute after this base, so "the 5th" is a window the test owns.
  private final OffsetDateTime base = OffsetDateTime.parse("2026-09-01T08:00:00Z");

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
            TestWiring.notificationService(dsl),
            TestWiring.magicLinkService(dsl),
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

  /** The seeded ledger, keyed for assertions. */
  private record Seeded(
      UUID orgId,
      UUID admin,
      UUID salma,
      UUID salmaOrder,
      UUID salmaTxn,
      UUID walkInTxn,
      UUID orphan,
      UUID claim,
      UUID cashTxn,
      UUID refundedOrphan,
      UUID refundDebit) {}

  /**
   * One org's ledger: Salma's InstaPay transfer matched to her CRM-customer order (ref
   * 770099887766, 1,240.00, the 5th); a walk-in "Ahmed Samir / 0100 555 7788" order paid by
   * InstaPay (640.00, the 5th); an orphan (120.00, the 5th); a shopper claim by Salma on an order
   * she named (300.00, the 4th, UNVERIFIED — a row, not money); a CASH sale (85.00, the 3rd); an
   * orphan (250.00, the 2nd) refunded back — its executed refund is the VERIFIED DEBIT (250.00,
   * occurred now). Seven rows.
   */
  private Seeded seedLedger() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");

    UUID salma = createCustomer(orgId, "Salma Hassan", "01001234471", "+201001234471");
    Order salmaOrder = seedPendingOrder(orgId, "1240.00", salma, null, null);
    UUID salmaTxn =
        verify(
            orgId,
            admin,
            PaymentProvider.INSTAPAY_MANUAL,
            "770099887766",
            "1240.00",
            salmaOrder.number(),
            at("2026-09-05T11:05:00Z"),
            "MATCHED");

    Order walkIn = seedPendingOrder(orgId, "640.00", null, "Ahmed Samir", "0100 555 7788");
    UUID walkInTxn =
        verify(
            orgId,
            admin,
            PaymentProvider.INSTAPAY_MANUAL,
            "770033445566",
            "640.00",
            walkIn.number(),
            at("2026-09-05T06:12:00Z"),
            "MATCHED");

    UUID orphan =
        verify(
            orgId,
            admin,
            PaymentProvider.INSTAPAY_MANUAL,
            "550011223344",
            "120.00",
            null,
            at("2026-09-05T08:02:00Z"),
            "ORPHAN");

    Order claimed = seedPendingOrder(orgId, "300.00", salma, null, null);
    UUID claim =
        seedUnverifiedClaim(
            orgId, "770011224455", "300.00", salma, claimed.id(), at("2026-09-04T06:15:00Z"));

    Order cashOrder = seedPendingOrder(orgId, "85.00", null, null, null);
    UUID cashTxn =
        verify(
            orgId,
            admin,
            PaymentProvider.CASH,
            "CASH-8f3a2c1d",
            "85.00",
            cashOrder.number(),
            at("2026-09-03T13:48:00Z"),
            "MATCHED");

    UUID refundedOrphan =
        verify(
            orgId,
            admin,
            PaymentProvider.INSTAPAY_MANUAL,
            "990011223344",
            "250.00",
            null,
            at("2026-09-02T10:55:00Z"),
            "ORPHAN");
    UUID refundId =
        service.refundOrphan(orgId, refundedOrphan, null, null, admin, false).refund().getId();
    refundService.execute(orgId, refundId, "IP-RETURN-1", admin);
    UUID refundDebit =
        dsl.select(PAYMENT_TRANSACTION.ID)
            .from(PAYMENT_TRANSACTION)
            .where(PAYMENT_TRANSACTION.ORG_ID.eq(orgId))
            .and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.DEBIT))
            .fetchOne(PAYMENT_TRANSACTION.ID);
    assertNotNull(refundDebit, "executing the refund writes the DEBIT");

    return new Seeded(
        orgId,
        admin,
        salma,
        salmaOrder.id(),
        salmaTxn,
        walkInTxn,
        orphan,
        claim,
        cashTxn,
        refundedOrphan,
        refundDebit);
  }

  // q — the four legs

  @Test
  void q_matchesTheReferenceByFragment() {
    Seeded s = seedLedger();

    assertEquals(List.of(s.salmaTxn()), ids(list(s, q("7766"))));
    // Case-insensitive, like the order number search.
    assertEquals(List.of(s.cashTxn()), ids(list(s, q("cash-8F3A"))));
  }

  @Test
  void q_matchesTheLinkedOrderNumber_throughThePaymentAndTheClaim() {
    Seeded s = seedLedger();
    String salmaNumber = numberOf(s.salmaOrder());

    // The matched transfer through payment.sales_order_id …
    assertEquals(
        List.of(s.salmaTxn()), ids(list(s, q(salmaNumber.substring(salmaNumber.length() - 5)))));
    // … and every linked row for the number's shared prefix (the claim through its claimed order).
    List<UUID> all = ids(list(s, q("SO-2026")));
    assertTrue(
        all.containsAll(List.of(s.salmaTxn(), s.walkInTxn(), s.claim(), s.cashTxn())),
        all.toString());
    assertTrue(!all.contains(s.orphan()), "an orphan links to no order");
  }

  @Test
  void q_matchesTheCustomer_crmNameFolded_walkInName_andPhoneDigits() {
    Seeded s = seedLedger();

    // Salma: the matched transfer through the order's CRM customer, the claim through the claimant.
    List<UUID> salma = ids(list(s, q("salma")));
    assertTrue(salma.containsAll(List.of(s.salmaTxn(), s.claim())), salma.toString());
    assertEquals(2, salma.size());
    // The walk-in name, folded in the query (no generated twin).
    assertEquals(List.of(s.walkInTxn()), ids(list(s, q("ahmed"))));
    // Phone digits — the walk-in contact stripped of its spaces …
    assertEquals(List.of(s.walkInTxn()), ids(list(s, q("5557788"))));
    // … and the CRM phone_e164, from Arabic-Indic digits.
    List<UUID> byCrmPhone = ids(list(s, q("١٢٣٤٤٧١")));
    assertTrue(byCrmPhone.containsAll(List.of(s.salmaTxn(), s.claim())), byCrmPhone.toString());
  }

  @Test
  void q_blankIsAbsent_andAnUnknownFragmentIsEmpty() {
    Seeded s = seedLedger();

    assertEquals(7, list(s, q("   ")).total(), "blank q is the whole ledger");
    assertEquals(0, list(s, q("zzz-nothing")).total());
  }

  // The window, the band, the method

  @Test
  void window_isHalfOpenOnOccurredAt() {
    Seeded s = seedLedger();
    ListFilter theFifth = window(at("2026-09-05T00:00:00Z"), at("2026-09-06T00:00:00Z"));

    List<UUID> rows = ids(list(s, theFifth));
    assertEquals(3, rows.size(), rows.toString());
    assertTrue(rows.containsAll(List.of(s.salmaTxn(), s.walkInTxn(), s.orphan())));

    // Start inclusive (the 06:12 row is in a window that starts at 06:12), end exclusive (the
    // 08:02 row is out of a window that ends at 08:02).
    assertEquals(
        List.of(s.walkInTxn()),
        ids(list(s, window(at("2026-09-05T06:12:00Z"), at("2026-09-05T08:02:00Z")))));
    assertEquals(
        List.of(s.orphan(), s.salmaTxn()),
        ids(
            list(
                s,
                window(at("2026-09-05T06:13:00Z"), at("2026-09-06T00:00:00Z"))
                    .sortedBy(ListFilter.Sort.OLDEST))));
  }

  @Test
  void band_isInclusive_andMinEqualsMaxFindsOneExactAmount() {
    Seeded s = seedLedger();

    assertEquals(List.of(s.salmaTxn()), ids(list(s, band("1240", "1240"))));
    List<UUID> upTo300 = ids(list(s, band(null, "300")));
    assertTrue(
        upTo300.containsAll(
            List.of(s.orphan(), s.claim(), s.cashTxn(), s.refundedOrphan(), s.refundDebit())),
        upTo300.toString());
    assertEquals(5, upTo300.size());
    assertEquals(
        List.of(s.walkInTxn(), s.salmaTxn()),
        ids(list(s, band("640", null).sortedBy(ListFilter.Sort.OLDEST))));
  }

  @Test
  void method_composesWithTheWindow_andNeverFlipsTheLedgerOrder() {
    Seeded s = seedLedger();
    ListFilter instaPayOnTheFifth =
        window(at("2026-09-05T00:00:00Z"), at("2026-09-06T00:00:00Z"))
            .withProvider(PaymentProvider.INSTAPAY_MANUAL);

    // Narrowing keeps the ledger's newest-first order (recorded_at DESC) — the page does not flip
    // to a queue because a method was chosen.
    TransactionPage page = list(s, instaPayOnTheFifth);
    assertEquals(3, page.total());
    assertEquals(
        ids(list(s, ListFilter.none())).stream().filter(ids(page)::contains).toList(), ids(page));
    assertEquals(
        List.of(s.cashTxn()), ids(list(s, ListFilter.none().withProvider(PaymentProvider.CASH))));
  }

  // Sort

  @Test
  void sort_overridesTheQueueVsLedgerRule_onOccurredAt() {
    Seeded s = seedLedger();

    // The ledger's own order is recorded_at DESC; an explicit oldest reads occurred_at ASC.
    List<UUID> oldest = ids(list(s, ListFilter.none().sortedBy(ListFilter.Sort.OLDEST)));
    assertEquals(
        List.of(
            s.refundedOrphan(),
            s.cashTxn(),
            s.claim(),
            s.walkInTxn(),
            s.orphan(),
            s.salmaTxn(),
            s.refundDebit()),
        oldest,
        "occurred_at ASC — the refund's DEBIT occurred at execution, i.e. now");

    // The orphan queue is a state view: oldest first by default, newest first when asked.
    ListFilter queue = new ListFilter(null, PaymentReconciliationStatus.ORPHAN, false, null, null);
    assertEquals(List.of(s.orphan()), ids(list(s, queue)));
    List<UUID> newest = ids(list(s, ListFilter.none().sortedBy(ListFilter.Sort.NEWEST)));
    assertEquals(s.refundDebit(), newest.get(0));
    assertEquals(s.salmaTxn(), newest.get(1));
  }

  // The summary and the row context

  @Test
  void summary_countsVerifiedMoneyOnly_inAndOut_overTheRowsPredicate() {
    Seeded s = seedLedger();

    TransactionPage all = list(s, ListFilter.none());
    assertEquals(7, all.stats().total());
    // 1240 + 640 + 120 + 85 + the refunded orphan's 250 credit; the UNVERIFIED claim is not money.
    assertEquals(
        0, new BigDecimal("2335.00").compareTo(all.stats().moneyIn()), all.stats().toString());
    assertEquals(
        0, new BigDecimal("250.00").compareTo(all.stats().moneyOut()), all.stats().toString());

    TransactionPage theFifth =
        list(
            s,
            window(at("2026-09-05T00:00:00Z"), at("2026-09-06T00:00:00Z"))
                .withProvider(PaymentProvider.INSTAPAY_MANUAL));
    assertEquals(3, theFifth.stats().total());
    assertEquals(0, new BigDecimal("2000.00").compareTo(theFifth.stats().moneyIn()));
    assertEquals(0, BigDecimal.ZERO.compareTo(theFifth.stats().moneyOut()));

    TransactionPage none = list(s, q("zzz-nothing"));
    assertEquals(0, none.stats().total());
    assertEquals(0, BigDecimal.ZERO.compareTo(none.stats().moneyIn()));
  }

  @Test
  void rows_carryTheMatchedOrderAndItsCustomer_claimsKeepTheirs_orphansNone() {
    Seeded s = seedLedger();

    TransactionPage page = list(s, ListFilter.none());
    assertEquals(s.salmaOrder(), page.matchedOrder(s.salmaTxn()).getId());
    assertEquals("Salma Hassan", page.matchedCustomer(s.salmaTxn()).getName());
    // The walk-in order: matched, its name on the order row, no CRM customer.
    assertEquals("Ahmed Samir", page.matchedOrder(s.walkInTxn()).getCustomerName());
    assertNull(page.matchedCustomer(s.walkInTxn()));
    // An orphan links to nothing; a claim links through its claimed order, not a payment.
    assertNull(page.matchedOrder(s.orphan()));
    assertNull(page.matchedOrder(s.claim()));
    assertNotNull(page.claimedOrders().get(claimedOrderOf(s.claim())));
    assertEquals("Salma Hassan", page.customers().get(s.salma()).getName());
  }

  @Test
  void otherOrg_neverLeaksThroughAnyLeg() {
    Seeded s = seedLedger();
    UUID other = createOrg("other");
    UUID otherAdmin = createUser("admin@other.test");
    UUID otherSalma = createCustomer(other, "Salma Hassan", "01001234471", "+201001234471");
    Order otherOrder = seedPendingOrder(other, "1240.00", otherSalma, null, null);
    verify(
        other,
        otherAdmin,
        PaymentProvider.INSTAPAY_MANUAL,
        "770099887799",
        "1240.00",
        otherOrder.number(),
        at("2026-09-05T11:05:00Z"),
        "MATCHED");

    assertEquals(List.of(s.salmaTxn()), ids(list(s, q("7766"))));
    List<UUID> salma = ids(list(s, q("salma")));
    assertEquals(2, salma.size(), salma.toString());
    assertEquals(
        0, new BigDecimal("2335.00").compareTo(list(s, ListFilter.none()).stats().moneyIn()));
  }

  // helpers

  private static TransactionPage list(Seeded s, ListFilter filter) {
    return service.list(s.orgId(), filter, 0, 20);
  }

  private static ListFilter q(String q) {
    return new ListFilter(null, null, null, null, null, null, q, null, null, null, null, null);
  }

  private static ListFilter window(OffsetDateTime from, OffsetDateTime to) {
    return new ListFilter(null, null, null, null, null, null, null, from, to, null, null, null);
  }

  private static ListFilter band(String min, String max) {
    return new ListFilter(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        min == null ? null : new BigDecimal(min),
        max == null ? null : new BigDecimal(max),
        null);
  }

  private static OffsetDateTime at(String iso) {
    return OffsetDateTime.parse(iso);
  }

  private static List<UUID> ids(TransactionPage page) {
    return page.items().stream().map(PaymentTransaction::getId).toList();
  }

  private String numberOf(UUID orderId) {
    return dsl.select(SALES_ORDER.ORDER_NUMBER)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.ORDER_NUMBER);
  }

  private UUID claimedOrderOf(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID);
  }

  private UUID verify(
      UUID orgId,
      UUID admin,
      PaymentProvider provider,
      String ref,
      String amount,
      String orderNumber,
      OffsetDateTime occurredAt,
      String expected) {
    VerifyResult r =
        service.verify(
            orgId,
            new VerifyCommand(
                provider,
                ref,
                new BigDecimal(amount),
                "EGP",
                null,
                orderNumber,
                null,
                null,
                "proof-blob",
                occurredAt),
            admin);
    assertEquals(expected, r.reconciliationStatus().name());
    return r.transaction().getId();
  }

  /** An UNVERIFIED shopper claim on the order they named — no service path leaves this state. */
  private UUID seedUnverifiedClaim(
      UUID orgId,
      String ref,
      String amount,
      UUID claimant,
      UUID claimedOrder,
      OffsetDateTime occurred) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, id)
        .set(PAYMENT_TRANSACTION.ORG_ID, orgId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.valueOf(
                PaymentProvider.INSTAPAY_MANUAL.dbLiteral()))
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, ref)
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.CURRENCY, "EGP")
        .set(
            PAYMENT_TRANSACTION.VERIFICATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.UNVERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, occurred)
        .set(PAYMENT_TRANSACTION.RECORDED_AT, occurred)
        .set(PAYMENT_TRANSACTION.CLAIMED_BY_CUSTOMER_ID, claimant)
        .set(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID, claimedOrder)
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

  private UUID createCustomer(UUID orgId, String name, String phone, String e164) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, name)
        .set(CUSTOMER.EMAIL, id + "@shop.test")
        .set(CUSTOMER.PHONE, phone)
        .set(CUSTOMER.PHONE_E164, e164)
        .execute();
    return id;
  }

  private Order seedPendingOrder(
      UUID orgId, String grandTotal, UUID customerId, String walkInName, String walkInPhone) {
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
        .set(SALES_ORDER.CUSTOMER_ID, customerId)
        .set(SALES_ORDER.CUSTOMER_NAME, walkInName)
        .set(SALES_ORDER.CUSTOMER_PHONE, walkInPhone)
        .set(SALES_ORDER.CREATED_AT, base.plusMinutes(occurredSeq.getAndIncrement()))
        .execute();
    return new Order(orderId, number);
  }
}
