package com.loai.inventory.api.refund;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE_LINE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.RefundStatus;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.FulfillmentStatus;
import com.loai.inventory.repository.generated.enums.InvoiceStatus;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CreditNoteService.InvoiceCreditNotes;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.OrderInvoices;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.RefundService.CreateCommand;
import com.loai.inventory.service.RefundService.RefundPage;
import com.loai.inventory.service.RefundService.RefundView;
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
 * Integration coverage for the money-side reads ({@code stories/money_reads.md}): the refunds
 * worklist ({@code GET /refunds} — queue-vs-ledger + per-row source context), the invoice crediting
 * story ({@code GET /credit-notes?sales_invoice_id=}), and the order billing story ({@code GET
 * /sales-orders/{id}/invoices}).
 *
 * <p>Drives the services directly against the real jOOQ repositories — no Tomcat. VIEWER
 * authorization and query-param parsing (400s) are enforced in the handler, as everywhere. Refunds
 * are created through the real flows (verify → direct refund, orphan refund) so the read sees
 * exactly what the write paths produce; {@code created_at} is then pinned by SQL so ordering
 * assertions are deterministic.
 */
@Testcontainers
class MoneyReadsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static RefundService refundService;
  static PaymentTransactionService txnService;
  static CreditNoteService creditNoteService;
  static InvoiceAdminService invoiceAdminService;

  private final AtomicInteger seq = new AtomicInteger(1);
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
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    txnService =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage());
    creditNoteService =
        new CreditNoteService(
            dsl,
            new CreditNoteRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
    invoiceAdminService =
        new InvoiceAdminService(
            dsl,
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new InvoiceService(
                new SalesInvoiceRepositoryFactoryImpl(),
                new PaymentRepositoryFactoryImpl(),
                new PaymentAllocationRepositoryFactoryImpl()));
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
        "TRUNCATE refund_allocation, refund, credit_note_line, credit_note, payment_allocation,"
            + " sales_invoice_line, sales_invoice, payment, payment_transaction, fulfillment,"
            + " fulfillment_line, sales_order_line, sales_order, customer, product, app_user, org,"
            + " invoice_number_counter, credit_note_number_counter RESTART IDENTITY CASCADE");
  }

  // refunds list: fixtures

  /**
   * The three refund shapes in one org, {@code created_at} pinned ascending: an <b>orphan</b>
   * payment-backed refund (no order), an <b>order-linked</b> payment-backed refund (later
   * executed), and a <b>CreditNote-backed</b> refund.
   */
  private record Seeded(
      UUID orgId,
      UUID admin,
      UUID orphanRefund,
      UUID orderRefund,
      UUID orderId,
      String orderNumber,
      UUID creditNoteRefund,
      UUID invoiceId,
      String creditNoteNumber) {}

  private Seeded seedRefunds() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");

    // r1 — orphan: a verified transfer matching no order, refunded from the orphan queue.
    UUID orphanTxn = verify(orgId, admin, "150.00", null, "ORPHAN");
    UUID r1 = txnService.refundOrphan(orgId, orphanTxn, null, null, admin, true).refund().getId();

    // r2 — order-linked: an UNDERPAID prepayment refunded directly (e.g. order cancelled).
    Order order = seedPendingOrder(orgId, "1000.00");
    UUID underpaidTxn = verify(orgId, admin, "400.00", order.number(), "UNDERPAID");
    UUID paymentId =
        dsl.select(PAYMENT.ID)
            .from(PAYMENT)
            .where(PAYMENT.PAYMENT_TRANSACTION_ID.eq(underpaidTxn))
            .fetchAny(PAYMENT.ID);
    UUID r2 =
        refundService
            .create(
                orgId,
                new CreateCommand(
                    null, paymentId, new BigDecimal("400.00"), "EGP", PaymentProvider.CASH, null),
                true)
            .getId();

    // r3 — CreditNote-backed: a credit against a delivered invoice.
    Invoice invoice = seedInvoice(orgId, null, "200.00", InvoiceStatus.ISSUED, base);
    String cnNumber = "CN-2026-0001";
    UUID cnId =
        seedCreditNote(
            orgId,
            invoice.id(),
            "60.00",
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.ISSUED,
            cnNumber,
            base);
    UUID r3 =
        refundService
            .create(
                orgId,
                new CreateCommand(
                    cnId, null, new BigDecimal("60.00"), "EGP", PaymentProvider.CASH, null),
                true)
            .getId();

    pinCreatedAt(r1, base.plusMinutes(1));
    pinCreatedAt(r2, base.plusMinutes(2));
    pinCreatedAt(r3, base.plusMinutes(3));

    return new Seeded(orgId, admin, r1, r2, order.id(), order.number(), r3, invoice.id(), cnNumber);
  }

  // refunds list: queue vs ledger

  @Test
  void pendingQueue_isOldestFirst_ledgerIsNewestFirst() {
    Seeded s = seedRefunds();

    // All three are PENDING: the to-execute queue is FIFO.
    RefundPage queue = refundService.list(s.orgId(), RefundStatus.PENDING, 0, 20);
    assertEquals(3, queue.total());
    assertEquals(List.of(s.orphanRefund(), s.orderRefund(), s.creditNoteRefund()), ids(queue));

    // Execute the middle one: it leaves the PENDING queue and shows under EXECUTED.
    refundService.execute(s.orgId(), s.orderRefund(), "IP-RETURN-1", s.admin());
    assertEquals(
        List.of(s.orphanRefund(), s.creditNoteRefund()),
        ids(refundService.list(s.orgId(), RefundStatus.PENDING, 0, 20)));
    assertEquals(
        List.of(s.orderRefund()), ids(refundService.list(s.orgId(), RefundStatus.EXECUTED, 0, 20)));

    // Unfiltered = the audit ledger, newest first, everything regardless of status.
    RefundPage ledger = refundService.list(s.orgId(), null, 0, 20);
    assertEquals(3, ledger.total());
    assertEquals(List.of(s.creditNoteRefund(), s.orderRefund(), s.orphanRefund()), ids(ledger));
  }

  @Test
  void pageAndSize_areClampedNotErrors() {
    Seeded s = seedRefunds();

    RefundPage page = refundService.list(s.orgId(), null, -3, 0);

    assertEquals(1, page.items().size());
    assertEquals(3, page.total());
  }

  @Test
  void listing_isOrgScoped() {
    Seeded s = seedRefunds();
    UUID otherOrg = createOrg("other");
    UUID otherAdmin = createUser("admin@other.test");
    UUID foreignTxn = verify(otherOrg, otherAdmin, "99.00", null, "ORPHAN");
    UUID foreign =
        txnService
            .refundOrphan(otherOrg, foreignTxn, null, null, otherAdmin, true)
            .refund()
            .getId();

    List<UUID> mine = ids(refundService.list(s.orgId(), null, 0, 20));

    assertEquals(3, mine.size());
    assertTrue(mine.stream().noneMatch(foreign::equals));
    assertEquals(List.of(foreign), ids(refundService.list(otherOrg, null, 0, 20)));
  }

  // refunds list: source context

  @Test
  void rows_carryTheirSourceContext_perShape() {
    Seeded s = seedRefunds();

    RefundPage page = refundService.list(s.orgId(), null, 0, 20);

    // Orphan payment-backed: money with no order — every context field absent.
    RefundView orphan = viewOf(page, s.orphanRefund());
    assertNotNull(orphan.refund().getPaymentId());
    assertNull(orphan.salesOrderId());
    assertNull(orphan.salesOrderNumber());
    assertNull(orphan.salesInvoiceId());
    assertNull(orphan.creditNoteNumber());

    // Order-linked payment-backed: the card can name its order.
    RefundView orderBacked = viewOf(page, s.orderRefund());
    assertEquals(s.orderId(), orderBacked.salesOrderId());
    assertEquals(s.orderNumber(), orderBacked.salesOrderNumber());
    assertNull(orderBacked.salesInvoiceId());
    assertNull(orderBacked.creditNoteNumber());

    // CreditNote-backed: the card can name the note and the credited invoice.
    RefundView cnBacked = viewOf(page, s.creditNoteRefund());
    assertNull(cnBacked.salesOrderId());
    assertNull(cnBacked.salesOrderNumber());
    assertEquals(s.invoiceId(), cnBacked.salesInvoiceId());
    assertEquals(s.creditNoteNumber(), cnBacked.creditNoteNumber());
  }

  // refunds list: credit_note_id filter (enumerating one note's refund history)

  @Test
  void list_filtersByCreditNoteId_narrowsWithoutReordering() {
    UUID orgId = createOrg("acme");
    Invoice invoice = seedInvoice(orgId, null, "200.00", InvoiceStatus.ISSUED, base);
    UUID cnA =
        seedCreditNote(
            orgId,
            invoice.id(),
            "60.00",
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.ISSUED,
            "CN-2026-0001",
            base);
    UUID cnB =
        seedCreditNote(
            orgId,
            invoice.id(),
            "40.00",
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.ISSUED,
            "CN-2026-0002",
            base);

    UUID rA1 = createCnRefund(orgId, cnA, "10.00");
    UUID rA2 = createCnRefund(orgId, cnA, "20.00");
    UUID rB = createCnRefund(orgId, cnB, "15.00");
    pinCreatedAt(rA1, base.plusMinutes(1));
    pinCreatedAt(rA2, base.plusMinutes(2));
    pinCreatedAt(rB, base.plusMinutes(3));

    // Ledger (no status) narrowed to cnA → only its two refunds, newest-first.
    RefundPage cnALedger = refundService.list(orgId, null, cnA, 0, 20);
    assertEquals(List.of(rA2, rA1), ids(cnALedger));
    assertEquals(2, cnALedger.total());

    // AND with a status filter → oldest-first queue, still only cnA's refunds.
    assertEquals(
        List.of(rA1, rA2), ids(refundService.list(orgId, RefundStatus.PENDING, cnA, 0, 20)));

    // cnB isolates its single refund; an unknown note is an empty page (a filter, not a 404).
    assertEquals(List.of(rB), ids(refundService.list(orgId, null, cnB, 0, 20)));
    assertEquals(0, refundService.list(orgId, null, UUID.randomUUID(), 0, 20).total());
  }

  // credit notes by invoice (the cap meter)

  @Test
  void listForInvoice_notesOldestFirst_creditedTotalIsTheCapGuardsSum() {
    UUID orgId = createOrg("acme");
    Invoice invoice = seedInvoice(orgId, null, "200.00", InvoiceStatus.ISSUED, base);
    UUID cn1 =
        seedCreditNote(
            orgId,
            invoice.id(),
            "50.00",
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.ISSUED,
            "CN-2026-0001",
            base.plusMinutes(1));
    UUID cn2 =
        seedCreditNote(
            orgId,
            invoice.id(),
            "30.00",
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.SETTLED,
            "CN-2026-0002",
            base.plusMinutes(2));
    UUID cn3 =
        seedCreditNote(
            orgId,
            invoice.id(),
            "20.00",
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.VOID,
            "CN-2026-0003",
            base.plusMinutes(3));

    InvoiceCreditNotes result = creditNoteService.listForInvoice(orgId, invoice.id(), null);

    // Unfiltered: every note including VOID, oldest first — the full crediting story.
    assertEquals(List.of(cn1, cn2, cn3), result.notes().stream().map(CreditNote::getId).toList());
    // The meter's numerator is the guard's own sum: ISSUED + SETTLED, VOID excluded.
    assertEquals(0, new BigDecimal("80.00").compareTo(result.creditedTotal()));
    // The denominator rides along on the header.
    assertEquals(invoice.id(), result.invoice().getId());
    assertEquals(invoice.number(), result.invoice().getInvoiceNumber());
    assertEquals(0, new BigDecimal("200.00").compareTo(result.invoice().getGrandTotal()));
  }

  @Test
  void listForInvoice_statusFilters_creditedTotalUnaffected() {
    UUID orgId = createOrg("acme");
    Invoice invoice = seedInvoice(orgId, null, "200.00", InvoiceStatus.ISSUED, base);
    UUID issued =
        seedCreditNote(
            orgId,
            invoice.id(),
            "50.00",
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.ISSUED,
            "CN-2026-0001",
            base.plusMinutes(1));
    seedCreditNote(
        orgId,
        invoice.id(),
        "20.00",
        com.loai.inventory.repository.generated.enums.CreditNoteStatus.VOID,
        "CN-2026-0002",
        base.plusMinutes(2));

    InvoiceCreditNotes result =
        creditNoteService.listForInvoice(orgId, invoice.id(), CreditNoteStatus.ISSUED);

    assertEquals(List.of(issued), result.notes().stream().map(CreditNote::getId).toList());
    // The filter narrows the story, never the cap arithmetic.
    assertEquals(0, new BigDecimal("50.00").compareTo(result.creditedTotal()));
  }

  @Test
  void listForInvoice_unknownOrForeignInvoice_is404() {
    UUID orgId = createOrg("acme");
    UUID otherOrg = createOrg("other");
    Invoice foreign = seedInvoice(otherOrg, null, "200.00", InvoiceStatus.ISSUED, base);

    assertThrows(
        NotFoundException.class,
        () -> creditNoteService.listForInvoice(orgId, UUID.randomUUID(), null));
    // Another org's invoice is invisible, not forbidden — scoping over the shared schema.
    assertThrows(
        NotFoundException.class, () -> creditNoteService.listForInvoice(orgId, foreign.id(), null));
  }

  // invoices by order (the billing story)

  @Test
  void listForOrder_everyStatusOldestFirst_withLinesAndHeader() {
    UUID orgId = createOrg("acme");
    Order order = seedPendingOrder(orgId, "500.00");
    // Oldest is VOID (a reissue story): both must appear, oldest first.
    Invoice voided =
        seedInvoice(orgId, order.id(), "200.00", InvoiceStatus.VOID, base.plusMinutes(1));
    Invoice reissued =
        seedInvoice(orgId, order.id(), "200.00", InvoiceStatus.ISSUED, base.plusMinutes(2));

    OrderInvoices result = invoiceAdminService.listForOrder(orgId, order.id());

    assertEquals(order.id(), result.order().getId());
    assertEquals(order.number(), result.order().getOrderNumber());
    assertEquals(
        List.of(voided.id(), reissued.id()),
        result.invoices().stream().map(v -> v.invoice().getId()).toList());
    for (var view : result.invoices()) {
      assertEquals(1, view.lines().size(), "each invoice must carry its lines");
    }
  }

  @Test
  void listForOrder_invoicelessOrderIsEmpty_unknownOrForeignOrderIs404() {
    UUID orgId = createOrg("acme");
    Order bare = seedPendingOrder(orgId, "100.00");
    UUID otherOrg = createOrg("other");
    Order foreign = seedPendingOrder(otherOrg, "100.00");

    assertTrue(invoiceAdminService.listForOrder(orgId, bare.id()).invoices().isEmpty());
    assertThrows(
        NotFoundException.class, () -> invoiceAdminService.listForOrder(orgId, UUID.randomUUID()));
    assertThrows(
        NotFoundException.class, () -> invoiceAdminService.listForOrder(orgId, foreign.id()));
  }

  // helpers

  private static List<UUID> ids(RefundPage page) {
    return page.items().stream().map(v -> v.refund().getId()).toList();
  }

  private static RefundView viewOf(RefundPage page, UUID refundId) {
    return page.items().stream()
        .filter(v -> v.refund().getId().equals(refundId))
        .findFirst()
        .orElseThrow();
  }

  private void pinCreatedAt(UUID refundId, OffsetDateTime at) {
    dsl.update(REFUND).set(REFUND.CREATED_AT, at).where(REFUND.ID.eq(refundId)).execute();
  }

  /** Record-and-verify a credit via the real service; asserts the expected reconciliation. */
  private UUID verify(UUID orgId, UUID admin, String amount, String orderNumber, String expected) {
    var r =
        txnService.verify(
            orgId,
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                "IPN-" + seq.getAndIncrement(),
                new BigDecimal(amount),
                "EGP",
                null,
                orderNumber,
                null,
                "paid via InstaPay",
                "proof-blob",
                base.plusMinutes(seq.getAndIncrement())),
            admin);
    assertEquals(expected, r.reconciliationStatus().name());
    return r.transaction().getId();
  }

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

  private record Order(UUID id, String number) {}

  private Order seedPendingOrder(UUID orgId, String grandTotal) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", seq.getAndIncrement());
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

  private record Invoice(UUID id, String number) {}

  /**
   * A persisted invoice row (+ its backing fulfillment + one line), seeded directly — the reads
   * under test never re-derive invoice state, so the full delivery machinery would add nothing.
   */
  private Invoice seedInvoice(
      UUID orgId, UUID salesOrderId, String grandTotal, InvoiceStatus status, OffsetDateTime at) {
    UUID orderId = salesOrderId != null ? salesOrderId : seedPendingOrder(orgId, grandTotal).id();
    UUID fulfillmentId = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillmentId)
        .set(FULFILLMENT.ORG_ID, orgId)
        .set(FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(FULFILLMENT.STATUS, FulfillmentStatus.DELIVERED)
        .execute();

    UUID invoiceId = UUID.randomUUID();
    String number = "INV-2026-" + String.format("%04d", seq.getAndIncrement());
    dsl.insertInto(SALES_INVOICE)
        .set(SALES_INVOICE.ID, invoiceId)
        .set(SALES_INVOICE.ORG_ID, orgId)
        .set(SALES_INVOICE.SALES_ORDER_ID, orderId)
        .set(SALES_INVOICE.FULFILLMENT_ID, fulfillmentId)
        .set(SALES_INVOICE.INVOICE_NUMBER, number)
        .set(SALES_INVOICE.STATUS, status)
        .set(SALES_INVOICE.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE.CUSTOMER_NAME, "Nadia")
        .set(SALES_INVOICE.ISSUED_AT, at)
        .set(SALES_INVOICE.CREATED_AT, at)
        .execute();

    dsl.insertInto(SALES_INVOICE_LINE)
        .set(SALES_INVOICE_LINE.ID, UUID.randomUUID())
        .set(SALES_INVOICE_LINE.SALES_INVOICE_ID, invoiceId)
        .set(SALES_INVOICE_LINE.DESCRIPTION, "item")
        .set(SALES_INVOICE_LINE.QUANTITY, 1)
        .set(SALES_INVOICE_LINE.UNIT_PRICE, new BigDecimal(grandTotal))
        .set(SALES_INVOICE_LINE.LINE_SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE_LINE.LINE_TOTAL, new BigDecimal(grandTotal))
        .execute();
    return new Invoice(invoiceId, number);
  }

  /** Create a PENDING CreditNote-backed refund (OWNER-bypassed) and return its id. */
  private UUID createCnRefund(UUID orgId, UUID creditNoteId, String amount) {
    return refundService
        .create(
            orgId,
            new CreateCommand(
                creditNoteId, null, new BigDecimal(amount), "EGP", PaymentProvider.CASH, null),
            true)
        .getId();
  }

  private UUID seedCreditNote(
      UUID orgId,
      UUID salesInvoiceId,
      String total,
      com.loai.inventory.repository.generated.enums.CreditNoteStatus status,
      String number,
      OffsetDateTime at) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CREDIT_NOTE)
        .set(CREDIT_NOTE.ID, id)
        .set(CREDIT_NOTE.ORG_ID, orgId)
        .set(CREDIT_NOTE.SALES_INVOICE_ID, salesInvoiceId)
        .set(
            CREDIT_NOTE.REASON,
            com.loai.inventory.repository.generated.enums.CreditNoteReason.RETURN)
        .set(CREDIT_NOTE.SUBTOTAL, new BigDecimal(total))
        .set(CREDIT_NOTE.TOTAL, new BigDecimal(total))
        .set(CREDIT_NOTE.CREDIT_NOTE_NUMBER, number)
        .set(CREDIT_NOTE.STATUS, status)
        .set(CREDIT_NOTE.ISSUED_AT, at)
        .set(CREDIT_NOTE.CREATED_AT, at)
        .execute();
    return id;
  }
}
