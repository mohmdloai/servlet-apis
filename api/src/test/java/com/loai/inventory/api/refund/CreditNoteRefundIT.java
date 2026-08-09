package com.loai.inventory.api.refund;

import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE;
import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE_NUMBER_COUNTER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.REFUND_ALLOCATION;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.common.exception.ApprovalRequiredException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CreditNoteReason;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.NumberSequenceReconciliationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CreditNoteService.IssueCommand;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.NumberSequenceReconciliationService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.RefundService.CreateCommand;
import com.loai.inventory.service.RefundService.Executed;
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
 * Integration coverage for the CreditNote + Refund slice ({@code stories/credit_note_refund.md}).
 * Boots one PostgreSQL container, runs all Flyway migrations, and drives {@link CreditNoteService}
 * + {@link RefundService} against the real jOOQ repository factories. A delivered, fully-paid
 * invoice (produced via the real {@link FulfillmentService} deliver flow) is the fixture that the
 * refund machinery operates on.
 */
@Testcontainers
class CreditNoteRefundIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static FulfillmentService fulfillmentService;
  static CreditNoteService creditNoteService;
  static RefundService refundService;
  static NumberSequenceReconciliationService reconciliationService;

  private final AtomicInteger seq = new AtomicInteger(1);
  private final ActorContext actor = ActorContext.user(UUID.randomUUID().toString());

  /**
   * A real app_user — payment_transaction.verified_by has an FK to it. Seeded once, survives
   * truncate.
   */
  static UUID actorId;

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

    InvoiceService invoiceService =
        new InvoiceService(
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    fulfillmentService =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            invoiceService,
            new com.loai.inventory.service.RefundService(
                dsl,
                new com.loai.inventory.repository.RefundRepositoryFactoryImpl(),
                new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl(),
                new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
                new com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl()),
            new com.loai.inventory.service.ReservationService(
                new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl()),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    creditNoteService =
        new CreditNoteService(
            dsl,
            new CreditNoteRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
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
    reconciliationService =
        new NumberSequenceReconciliationService(
            dsl, new NumberSequenceReconciliationRepositoryFactoryImpl());

    actorId = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.APP_USER)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.ID, actorId)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.EMAIL, "refund-admin@test")
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.PASSWORD_HASH, "x")
        .set(
            com.loai.inventory.repository.generated.Tables.APP_USER.ACTOR_TYPE,
            com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
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
            + " fulfillment_line, inventory_reservation, inventory_log, inventory, sales_order_line,"
            + " sales_order, customer, product, org, invoice_number_counter,"
            + " credit_note_number_counter RESTART IDENTITY CASCADE");
  }

  // CreditNote-backed

  /**
   * Number-sequence integrity for credit notes ({@code stories/number_sequence_integrity.md}): the
   * same drift hazard the story confirmed on credit-note numbering. A counter behind the table
   * re-mints a taken CN number; that must be a clean 409 (not a raw 500), roll back, and be
   * healable by the reconcile routine.
   */
  @Test
  void issueCreditNote_counterDriftedBehindTable_is409_thenReconcileHeals() {
    int year = OffsetDateTime.now(ZoneOffset.UTC).getYear();
    Fixture f =
        deliverPaidInvoice("60.00", "60.00"); // cap = 60.00; leaves room for two 10.00 notes

    // First credit note → CN-YYYY-0001; the counter advances to 2.
    var i1 = creditNoteService.issue(f.org, returnCommand(f.invoiceId, "10.00"), false);
    assertEquals("CN-" + year + "-0001", i1.creditNote().getCreditNoteNumber());

    // Simulate drift: rewind the credit-note counter behind the table so the next claim regenerates
    // the taken CN-YYYY-0001.
    int reset =
        dsl.update(CREDIT_NOTE_NUMBER_COUNTER)
            .set(CREDIT_NOTE_NUMBER_COUNTER.NEXT_VAL, 1L)
            .where(CREDIT_NOTE_NUMBER_COUNTER.ORG_ID.eq(f.org))
            .execute();
    assertEquals(1, reset);

    // Second credit note re-mints CN-YYYY-0001 → (org, credit_note_number) unique violation,
    // translated to a 409 that names the remedy — not an "Unexpected error" 500.
    ConflictException ex =
        assertThrows(
            ConflictException.class,
            () -> creditNoteService.issue(f.org, returnCommand(f.invoiceId, "10.00"), false));
    assertTrue(ex.getMessage().contains("Credit-note number sequence is out of sync"));

    // Rolled back cleanly: still exactly one credit note against the invoice.
    assertEquals(
        1, dsl.fetchCount(dsl.selectFrom(CREDIT_NOTE).where(CREDIT_NOTE.ORG_ID.eq(f.org))));

    // Repair: reconcile realigns only the credit-note counter (the invoice counter is already
    // ahead), forward-only to MAX+1 = 2.
    NumberSequenceReconciliationService.Summary summary = reconciliationService.reconcile();
    assertEquals(1, summary.counted());
    assertEquals("CREDIT_NOTE", summary.realignments().get(0).documentType());

    // The retry now mints the next gapless number.
    var i2 = creditNoteService.issue(f.org, returnCommand(f.invoiceId, "10.00"), false);
    assertEquals("CN-" + year + "-0002", i2.creditNote().getCreditNoteNumber());
    assertEquals(
        2, dsl.fetchCount(dsl.selectFrom(CREDIT_NOTE).where(CREDIT_NOTE.ORG_ID.eq(f.org))));
  }

  /**
   * Full return: refund covers the whole invoice → payment REFUNDED, CN SETTLED, invoice stays
   * PAID.
   */
  @Test
  void creditNoteBacked_fullReturn_refundsPayment_settlesCreditNote_invoiceStaysPaid() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");

    UUID cnId =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "30.00"), false)
            .creditNote()
            .getId();
    assertEquals("ISSUED", creditNoteStatus(cnId));

    var refund =
        refundService.create(
            f.org,
            new CreateCommand(
                cnId, null, new BigDecimal("30.00"), "EGP", PaymentProvider.CASH, null),
            false);
    assertEquals("PENDING", refundStatus(refund.getId()));

    Executed executed = refundService.execute(f.org, refund.getId(), "IP-RET-1", actorId);

    // Refund EXECUTED + linked DEBIT transaction (VERIFIED).
    assertEquals("EXECUTED", refundStatus(refund.getId()));
    assertNotNull(executed.refund().getPaymentTransactionId());
    assertEquals(
        PaymentDirection.DEBIT, debitDirection(executed.refund().getPaymentTransactionId()));
    assertEquals(
        PaymentVerificationStatus.VERIFIED,
        debitVerification(executed.refund().getPaymentTransactionId()));

    // Exactly one refund_allocation summing to the refund amount.
    assertEquals(1, refundAllocationCount(refund.getId()));
    assertEquals(0, new BigDecimal("30.00").compareTo(refundAllocationSum(refund.getId())));

    // Payment fully refunded; gross rule: invoice stays PAID with paid_amount unchanged.
    assertEquals("REFUNDED", paymentStatus(f.paymentId));
    assertEquals(0, new BigDecimal("30.00").compareTo(paymentRefunded(f.paymentId)));
    assertEquals("PAID", invoiceStatus(f.invoiceId));
    assertEquals(0, new BigDecimal("30.00").compareTo(invoicePaid(f.invoiceId)));

    // CreditNote fully covered → SETTLED.
    assertEquals("SETTLED", creditNoteStatus(cnId));
  }

  /** Partial then settling refund: PARTIALLY_REFUNDED → REFUNDED, CN ISSUED → SETTLED. */
  @Test
  void creditNoteBacked_partialThenRemainder_settlesOnFullCover() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    UUID cnId =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "30.00"), false)
            .creditNote()
            .getId();

    UUID r1 = createCnRefund(f.org, cnId, "10.00");
    refundService.execute(f.org, r1, null, actorId);
    assertEquals("PARTIALLY_REFUNDED", paymentStatus(f.paymentId));
    assertEquals("ISSUED", creditNoteStatus(cnId));

    UUID r2 = createCnRefund(f.org, cnId, "20.00");
    refundService.execute(f.org, r2, null, actorId);
    assertEquals("REFUNDED", paymentStatus(f.paymentId));
    assertEquals(0, new BigDecimal("30.00").compareTo(paymentRefunded(f.paymentId)));
    assertEquals("SETTLED", creditNoteStatus(cnId));
    // Invoice never touched.
    assertEquals("PAID", invoiceStatus(f.invoiceId));
  }

  /**
   * The CN reads expose the executed/remaining meter (G1): the detail read ({@code get}) carries
   * {@code refundedTotal}, and the invoice's credit-note list carries it per note — a client reads
   * the meter straight off the note instead of reconstructing it by scanning the refund ledger.
   */
  @Test
  void creditNoteReads_exposeRefundedTotal_detailAndList() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    UUID cnId =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "30.00"), false)
            .creditNote()
            .getId();

    // Nothing refunded yet.
    assertEquals(0, BigDecimal.ZERO.compareTo(creditNoteService.get(f.org, cnId).refundedTotal()));

    // Partial refund of 10 → both reads report 10 executed.
    refundService.execute(f.org, createCnRefund(f.org, cnId, "10.00"), null, actorId);
    assertEquals(
        0, new BigDecimal("10.00").compareTo(creditNoteService.get(f.org, cnId).refundedTotal()));
    assertEquals(
        0,
        new BigDecimal("10.00")
            .compareTo(
                creditNoteService
                    .listForInvoice(f.org, f.invoiceId, null)
                    .refundedTotals()
                    .getOrDefault(cnId, BigDecimal.ZERO)));

    // Settle the remaining 20 → 30 executed on both reads.
    refundService.execute(f.org, createCnRefund(f.org, cnId, "20.00"), null, actorId);
    assertEquals(
        0, new BigDecimal("30.00").compareTo(creditNoteService.get(f.org, cnId).refundedTotal()));
    assertEquals(
        0,
        new BigDecimal("30.00")
            .compareTo(
                creditNoteService
                    .listForInvoice(f.org, f.invoiceId, null)
                    .refundedTotals()
                    .getOrDefault(cnId, BigDecimal.ZERO)));
  }

  // Direct-from-Payment

  /**
   * Overpayment: refund the unallocated excess → no refund_allocation rows; payment back to
   * ALLOCATED.
   */
  @Test
  void directOverpayment_refundsUnallocated_noAllocationRows_paymentAllocated() {
    // Invoice 30, but the customer paid 130 → 100 left unallocated after delivery.
    Fixture f = deliverPaidInvoice("30.00", "130.00");
    assertEquals("PARTIALLY_ALLOCATED", paymentStatus(f.paymentId));
    assertEquals(0, new BigDecimal("100.00").compareTo(paymentUnallocated(f.paymentId)));

    UUID refundId =
        refundService
            .create(
                f.org,
                new CreateCommand(
                    null, f.paymentId, new BigDecimal("100.00"), "EGP", PaymentProvider.CASH, null),
                false)
            .getId();
    refundService.execute(f.org, refundId, null, actorId);

    assertEquals("EXECUTED", refundStatus(refundId));
    assertEquals(0, refundAllocationCount(refundId)); // direct path → zero rows
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(f.paymentId)));
    assertEquals(0, new BigDecimal("100.00").compareTo(paymentRefunded(f.paymentId)));
    assertEquals("ALLOCATED", paymentStatus(f.paymentId)); // 30 still allocated to the invoice
    // Invoice untouched.
    assertEquals("PAID", invoiceStatus(f.invoiceId));
  }

  /** Orphan payment (no allocation) fully refunded → REFUNDED. */
  @Test
  void directOrphanPayment_fullyRefunded_paymentRefunded() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Zed", "zed@acme.test");
    UUID paymentId = seedOrphanPayment(org, customer, "200.00");

    UUID refundId =
        refundService
            .create(
                org,
                new CreateCommand(
                    null, paymentId, new BigDecimal("200.00"), "EGP", PaymentProvider.CASH, null),
                false)
            .getId();
    refundService.execute(org, refundId, null, actorId);

    assertEquals(0, refundAllocationCount(refundId));
    assertEquals("REFUNDED", paymentStatus(paymentId));
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(paymentId)));
  }

  /**
   * Partial direct refund of an orphan payment (nothing allocated) lands on PARTIALLY_REFUNDED —
   * not the phantom PARTIALLY_ALLOCATED the allocation-based derivation would otherwise produce.
   */
  @Test
  void directOrphanPayment_partiallyRefunded_isPartiallyRefunded() {
    UUID org = createOrg("acme");
    UUID paymentId = seedOrphanPayment(org, null, "100.00");

    UUID refundId =
        refundService
            .create(
                org,
                new CreateCommand(
                    null, paymentId, new BigDecimal("60.00"), "EGP", PaymentProvider.CASH, null),
                false)
            .getId();
    refundService.execute(org, refundId, null, actorId);

    assertEquals(0, refundAllocationCount(refundId)); // direct path → zero rows
    assertEquals("PARTIALLY_REFUNDED", paymentStatus(paymentId));
    assertEquals(0, new BigDecimal("60.00").compareTo(paymentRefunded(paymentId)));
    assertEquals(0, new BigDecimal("40.00").compareTo(paymentUnallocated(paymentId)));
  }

  // guards

  /** Over-refund is rejected on both paths before any side effect. */
  @Test
  void overRefund_isRejected_bothPaths() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    UUID cnId =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "30.00"), false)
            .creditNote()
            .getId();
    // CreditNote path: amount beyond the note's remaining.
    assertThrows(
        ConflictException.class,
        () ->
            refundService.create(
                f.org,
                new CreateCommand(
                    cnId, null, new BigDecimal("31.00"), "EGP", PaymentProvider.CASH, null),
                false));

    // Direct path: amount beyond the payment's unallocated.
    UUID orphan = seedOrphanPayment(f.org, null, "50.00");
    assertThrows(
        ConflictException.class,
        () ->
            refundService.create(
                f.org,
                new CreateCommand(
                    null, orphan, new BigDecimal("51.00"), "EGP", PaymentProvider.CASH, null),
                false));
  }

  /** Cumulative credit notes against one invoice can't exceed its grand total. */
  @Test
  void cumulativeCreditNotes_cannotExceedInvoiceGrandTotal() {
    Fixture f = deliverPaidInvoice("100.00", "100.00");
    // First credit note of 90 is fine (90 ≤ 100).
    UUID cn1 =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "90.00"), true)
            .creditNote()
            .getId();
    assertEquals("ISSUED", creditNoteStatus(cn1));

    // A second of 80 would push the cumulative to 170 > 100 → rejected at issuance.
    assertThrows(
        ValidationException.class,
        () -> creditNoteService.issue(f.org, returnCommand(f.invoiceId, "80.00"), true));

    // Topping up to exactly the remaining 10 (cumulative 100) is still allowed.
    UUID cn2 =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "10.00"), true)
            .creditNote()
            .getId();
    assertEquals("ISSUED", creditNoteStatus(cn2));
  }

  /**
   * Bad per-line input (non-positive quantity, negative money) is a 400 from the service's own
   * validation — not a 500 leaking out of {@code CreditNoteLine.create}'s IllegalArgumentException.
   */
  @Test
  void invalidLineInput_isRejectedAsValidation() {
    Fixture f = deliverPaidInvoice("100.00", "100.00");
    assertThrows(
        ValidationException.class,
        () -> creditNoteService.issue(f.org, badLineCommand(f.invoiceId, 0, "10.00", "0"), true));
    assertThrows(
        ValidationException.class,
        () -> creditNoteService.issue(f.org, badLineCommand(f.invoiceId, 1, "-1.00", "0"), true));
    assertThrows(
        ValidationException.class,
        () ->
            creditNoteService.issue(f.org, badLineCommand(f.invoiceId, 1, "10.00", "-0.1"), true));
  }

  /** Exactly one authorization source — both or neither set is a 400. */
  @Test
  void exactlyOneSource_isEnforced() {
    UUID org = createOrg("acme");
    assertThrows(
        ValidationException.class,
        () ->
            refundService.create(
                org,
                new CreateCommand(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new BigDecimal("1.00"),
                    "EGP",
                    PaymentProvider.CASH,
                    null),
                false));
    assertThrows(
        ValidationException.class,
        () ->
            refundService.create(
                org,
                new CreateCommand(
                    null, null, new BigDecimal("1.00"), "EGP", PaymentProvider.CASH, null),
                false));
  }

  /** A second execute on an already-EXECUTED refund is rejected; no second DEBIT transaction. */
  @Test
  void doubleExecute_isRejected_noSecondDebit() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    UUID cnId =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "30.00"), false)
            .creditNote()
            .getId();
    UUID refundId = createCnRefund(f.org, cnId, "30.00");
    refundService.execute(f.org, refundId, "IP-RET-2", actorId);

    assertThrows(
        ConflictException.class, () -> refundService.execute(f.org, refundId, "IP-RET-2", actorId));
    assertEquals(1, debitTxnCount(f.org)); // exactly one DEBIT in the org
  }

  /**
   * Empirical proof of the "refund-execution failure has no operational trail" gap. We force
   * execute() to fail deterministically — a DEBIT with the same {@code (provider, provider_ref)}
   * already exists, so {@code insertIfAbsent} reports not-inserted and the whole execute() txn
   * rolls back. The refund is correctly left PENDING (retry-able), but there is <em>nowhere</em> to
   * record why it failed: the {@code refund} table (V26) has no {@code last_error} / retry-count /
   * next-attempt column, so the only trace the failure ever happened is the thrown exception —
   * nothing is persisted for an operator to see or drive a retry from.
   */
  @Test
  void refundExecuteFailure_leavesPending_withNoPersistedErrorTrail() {
    UUID org = createOrg("acme");
    UUID paymentId = seedOrphanPayment(org, null, "100.00");
    UUID refundId =
        refundService
            .create(
                org,
                new CreateCommand(
                    null,
                    paymentId,
                    new BigDecimal("100.00"),
                    "EGP",
                    PaymentProvider.INSTAPAY_MANUAL,
                    null),
                false)
            .getId();
    assertEquals("PENDING", refundStatus(refundId));

    // Pre-seed the exact DEBIT (provider, provider_ref) execute() will attempt → forced
    // ConflictException.
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, UUID.randomUUID())
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "COLLIDE-1")
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.DEBIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal("100.00"))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusHours(1))
        .execute();

    assertThrows(
        ConflictException.class, () -> refundService.execute(org, refundId, "COLLIDE-1", actorId));

    // Left exactly PENDING with executed_at/txn unset — good for retry, but ZERO persisted trail of
    // the failure (no last_error column exists to assert against; its absence is the gap).
    assertEquals("PENDING", refundStatus(refundId));
    assertNull(refundTxnId(refundId));
  }

  /** Voiding: blocked once a refund has executed; allowed for an unused ISSUED note. */
  @Test
  void voidGuards() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    UUID usedCn =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "30.00"), false)
            .creditNote()
            .getId();
    UUID refundId = createCnRefund(f.org, usedCn, "30.00");
    refundService.execute(f.org, refundId, null, actorId);
    assertThrows(ConflictException.class, () -> creditNoteService.voidNote(f.org, usedCn));

    // A fresh, unused credit note voids cleanly.
    Fixture g = deliverPaidInvoice("30.00", "30.00");
    UUID freshCn =
        creditNoteService
            .issue(g.org, returnCommand(g.invoiceId, "30.00"), false)
            .creditNote()
            .getId();
    creditNoteService.voidNote(g.org, freshCn);
    assertEquals("VOID", creditNoteStatus(freshCn));
  }

  // threshold

  /** Above the org threshold, issuing a credit note requires OWNER; below, MANAGER suffices. */
  @Test
  void threshold_escalatesCreditNoteIssuanceToOwner() {
    // Default org threshold is 500. A 600 invoice → 600 credit note exceeds it.
    Fixture f = deliverPaidInvoice("600.00", "600.00");
    IssueCommand cmd = returnCommand(f.invoiceId, "600.00");

    // MANAGER (not owner/admin) blocked above threshold.
    assertThrows(AuthorizationException.class, () -> creditNoteService.issue(f.org, cmd, false));
    // OWNER/admin allowed.
    UUID cnId = creditNoteService.issue(f.org, cmd, true).creditNote().getId();
    assertEquals("ISSUED", creditNoteStatus(cnId));
  }

  /** Above the org threshold, a direct refund requires OWNER; a small one does not. */
  @Test
  void threshold_escalatesDirectRefundToOwner() {
    UUID org = createOrg("acme");
    UUID payBig = seedOrphanPayment(org, null, "600.00");
    assertThrows(
        AuthorizationException.class,
        () ->
            refundService.create(
                org,
                new CreateCommand(
                    null, payBig, new BigDecimal("600.00"), "EGP", PaymentProvider.CASH, null),
                false));
    // Owner/admin clears it.
    var ok =
        refundService.create(
            org,
            new CreateCommand(
                null, payBig, new BigDecimal("600.00"), "EGP", PaymentProvider.CASH, null),
            true);
    assertEquals("PENDING", refundStatus(ok.getId()));

    // A small direct refund (≤ threshold) needs no owner.
    UUID paySmall = seedOrphanPayment(org, null, "100.00");
    var small =
        refundService.create(
            org,
            new CreateCommand(
                null, paySmall, new BigDecimal("100.00"), "EGP", PaymentProvider.CASH, null),
            false);
    assertEquals("PENDING", refundStatus(small.getId()));
  }

  /** Owner can raise the threshold; a previously-blocked credit note then issues for a MANAGER. */
  @Test
  void threshold_isConfigurablePerOrg() {
    UUID org = createOrg("acme");
    dsl.update(ORG)
        .set(ORG.REFUND_APPROVAL_THRESHOLD, new BigDecimal("1000.00"))
        .where(ORG.ID.eq(org))
        .execute();
    Fixture f = deliverPaidInvoiceForOrg(org, "600.00", "600.00");
    UUID cnId =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "600.00"), false)
            .creditNote()
            .getId();
    assertEquals("ISSUED", creditNoteStatus(cnId)); // 600 < raised threshold 1000
  }

  // threshold — structuring (D1)

  /**
   * D1: the OWNER bar is the running total drawn from one payment, not the size of one call. Three
   * sub-threshold direct refunds against the same payment used to drain an above-threshold sum
   * without an OWNER ever seeing it.
   */
  @Test
  void threshold_directRefundStructuringIsGatedOnTheAggregate() {
    UUID org = createOrg("acme");
    UUID payment = seedOrphanPayment(org, null, "900.00");

    // Sub-threshold and nothing before it: a MANAGER may create it.
    UUID first = refundService.create(org, directRefund(payment, "300.00"), false).getId();
    assertEquals("PENDING", refundStatus(first));

    // Also sub-threshold on its own — but 300 + 300 crosses the org's 500 bar, so it escalates.
    assertThrows(
        AuthorizationException.class,
        () -> refundService.create(org, directRefund(payment, "300.00"), false));

    // The very same call succeeds for an OWNER.
    UUID second = refundService.create(org, directRefund(payment, "300.00"), true).getId();
    assertEquals("PENDING", refundStatus(second));

    // The total counts live refunds only: cancelling one gives its room back.
    refundService.cancel(org, second, "customer changed their mind");
    UUID third = refundService.create(org, directRefund(payment, "200.00"), false).getId();
    assertEquals("PENDING", refundStatus(third)); // 300 + 200 == 500, not over it
  }

  /**
   * D1, the credit-note half: the cumulative credited total of one invoice is what meets the
   * threshold. The cap on N notes was the invoice's grand total, so a MANAGER could credit (and
   * then refund) far above the bar in sub-threshold slices.
   */
  @Test
  void threshold_creditNoteStructuringIsGatedOnTheInvoiceTotal() {
    Fixture f = deliverPaidInvoice("900.00", "900.00");

    UUID first =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "300.00"), false)
            .creditNote()
            .getId();
    assertEquals("ISSUED", creditNoteStatus(first));

    // 300 + 300 = 600 > 500, even though neither note alone reaches the bar.
    assertThrows(
        AuthorizationException.class,
        () -> creditNoteService.issue(f.org, returnCommand(f.invoiceId, "300.00"), false));

    UUID second =
        creditNoteService
            .issue(f.org, returnCommand(f.invoiceId, "300.00"), true)
            .creditNote()
            .getId();
    assertEquals("ISSUED", creditNoteStatus(second));
  }

  // D12: the refusal has to be machine-readable, or the client cannot explain it

  /**
   * The above-threshold refusal carries the role that could approve it and the two numbers that
   * produced it. Without them the 403 is indistinguishable from an ordinary permission denial, and
   * the admin app rendered it as one — "You don't have permission to do that." — which is wrong
   * rather than merely vague, since the caller *does* have permission.
   *
   * <p>Asserted on the <b>requested amount specifically</b>, because since D1 that is the payment's
   * running total (300 + 300) and not the 300 in front of the user. A client cannot derive it,
   * which is exactly why it has to be on the wire.
   */
  @Test
  void threshold_refusalCarriesTheRoleAndTheNumbers() {
    UUID org = createOrg("acme");
    UUID payment = seedOrphanPayment(org, null, "900.00");
    refundService.create(org, directRefund(payment, "300.00"), false);

    ApprovalRequiredException e =
        assertThrows(
            ApprovalRequiredException.class,
            () -> refundService.create(org, directRefund(payment, "300.00"), false));

    assertEquals("OWNER", e.getRequiredRole());
    assertEquals(0, new BigDecimal("500.00").compareTo(e.getThresholdAmount()), "the org's bar");
    assertEquals(
        0,
        new BigDecimal("600.00").compareTo(e.getRequestedAmount()),
        "the payment's running total, not this call's 300 — the D1 rule, on the wire");
    // Still a 403 and still an AuthorizationException: this widens the body, it does not move the
    // status or break callers that only catch the supertype.
    assertEquals(403, e.getStatusCode());
    assertTrue(e instanceof AuthorizationException);
  }

  /** The credit-note half carries the same three facts, sourced from the invoice's total. */
  @Test
  void threshold_creditNoteRefusalCarriesTheRoleAndTheNumbers() {
    Fixture f = deliverPaidInvoice("900.00", "900.00");
    creditNoteService.issue(f.org, returnCommand(f.invoiceId, "300.00"), false);

    ApprovalRequiredException e =
        assertThrows(
            ApprovalRequiredException.class,
            () -> creditNoteService.issue(f.org, returnCommand(f.invoiceId, "300.00"), false));

    assertEquals("OWNER", e.getRequiredRole());
    assertEquals(0, new BigDecimal("500.00").compareTo(e.getThresholdAmount()));
    assertEquals(
        0,
        new BigDecimal("600.00").compareTo(e.getRequestedAmount()),
        "the invoice's cumulative credited total");
  }

  /**
   * The mapping the five money handlers now share. {@code ApiErrors.body} is the single place an
   * exception decides its own wire shape — the point of the D12 refactor — so proving it here
   * proves every route that can raise this, rather than proving one handler and hoping.
   */
  @Test
  void threshold_refusalSerializesTheFields() {
    UUID org = createOrg("acme");
    UUID payment = seedOrphanPayment(org, null, "900.00");
    refundService.create(org, directRefund(payment, "300.00"), false);

    ApprovalRequiredException e =
        assertThrows(
            ApprovalRequiredException.class,
            () -> refundService.create(org, directRefund(payment, "300.00"), false));

    ApiError body = ApiErrors.body(e);
    assertEquals("OWNER", body.getRequiredRole());
    assertEquals(0, new BigDecimal("500.00").compareTo(body.getThresholdAmount()));
    assertEquals(0, new BigDecimal("600.00").compareTo(body.getRequestedAmount()));
    assertEquals(403, body.getStatus());

    // And an ordinary 403 is untouched — the fields are absent, so Jackson omits them and the
    // envelope is byte-identical to what every existing client already parses.
    ApiError plain = ApiErrors.body(new AuthorizationException("nope"));
    assertNull(plain.getRequiredRole());
    assertNull(plain.getThresholdAmount());
    assertNull(plain.getRequestedAmount());
  }

  // fixtures & helpers

  private record Fixture(UUID org, UUID customer, UUID invoiceId, UUID paymentId) {}

  private CreateCommand directRefund(UUID paymentId, String amount) {
    return new CreateCommand(
        null, paymentId, new BigDecimal(amount), "EGP", PaymentProvider.CASH, null);
  }

  private IssueCommand badLineCommand(
      UUID invoiceId, int quantity, String unitPrice, String taxRate) {
    return new IssueCommand(
        invoiceId,
        CreditNoteReason.RETURN,
        "customer returned goods",
        List.of(
            new CreditNoteService.LineSpec(
                null,
                "returned item",
                quantity,
                new BigDecimal(unitPrice),
                new BigDecimal(taxRate))));
  }

  private IssueCommand returnCommand(UUID invoiceId, String total) {
    // qty=1 at unit_price=total, taxRate 0 → line total == total.
    return new IssueCommand(
        invoiceId,
        CreditNoteReason.RETURN,
        "customer returned goods",
        List.of(
            new CreditNoteService.LineSpec(
                null, "returned item", 1, new BigDecimal(total), BigDecimal.ZERO)));
  }

  private UUID createCnRefund(UUID org, UUID cnId, String amount) {
    return refundService
        .create(
            org,
            new CreateCommand(
                cnId, null, new BigDecimal(amount), "EGP", PaymentProvider.CASH, null),
            false)
        .getId();
  }

  /** Deliver one fully/partly-paid invoice in a fresh org and return its ids. */
  private Fixture deliverPaidInvoice(String grandTotal, String paymentAmount) {
    UUID org = createOrg("acme");
    return deliverPaidInvoiceForOrg(org, grandTotal, paymentAmount);
  }

  private Fixture deliverPaidInvoiceForOrg(UUID org, String grandTotal, String paymentAmount) {
    UUID customer = createCustomer(org, "Nadia", "nadia@acme.test");
    UUID product = createProduct(org, "SKU");
    int qty = new BigDecimal(grandTotal).divide(new BigDecimal("10.00")).intValue();
    createInventory(org, product, qty + 5, qty);

    UUID orderId = seedPaidOrder(org, customer, product, qty, grandTotal, paymentAmount);
    UUID lineId =
        dsl.select(SALES_ORDER_LINE.ID)
            .from(SALES_ORDER_LINE)
            .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(orderId))
            .fetchAny(SALES_ORDER_LINE.ID);

    UUID f =
        fulfillmentService
            .create(org, orderId, List.of(new LineInput(lineId)), null, null, null, actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, f, actor);
    DeliveredView view = fulfillmentService.markDelivered(org, f, actor);

    UUID paymentId =
        dsl.select(PAYMENT.ID)
            .from(PAYMENT)
            .where(PAYMENT.SALES_ORDER_ID.eq(orderId))
            .fetchAny(PAYMENT.ID);
    return new Fixture(org, customer, view.invoice().getId(), paymentId);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
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

  private UUID createCustomer(UUID org, String name, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.CUSTOMER)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.ID, id)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.ORG_ID, org)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.NAME, name)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.EMAIL, id + "-" + email)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private void createInventory(UUID org, UUID product, int stockQty, int reservedQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, reservedQty)
        .execute();
  }

  private UUID seedPaidOrder(
      UUID org, UUID customer, UUID product, int qty, String grandTotal, String paymentAmount) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    BigDecimal grand = new BigDecimal(grandTotal);
    BigDecimal prepaid = new BigDecimal(paymentAmount);
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PAID)
        .set(SALES_ORDER.SUBTOTAL, grand)
        .set(SALES_ORDER.GRAND_TOTAL, grand)
        .set(SALES_ORDER.PREPAID_AMOUNT, prepaid)
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();

    UUID lineId = UUID.randomUUID();
    BigDecimal lineTotal = new BigDecimal("10.00").multiply(BigDecimal.valueOf(qty));
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, lineId)
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, product)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, lineTotal)
        .set(SALES_ORDER_LINE.LINE_TOTAL, lineTotal)
        .execute();

    UUID reservationId = UUID.randomUUID();
    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, reservationId)
        .set(INVENTORY_RESERVATION.ORG_ID, org)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, product)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, qty)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .execute();

    seedPayment(org, customer, orderId, paymentAmount);
    return orderId;
  }

  private void seedPayment(UUID org, UUID customer, UUID orderId, String amount) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusHours(2))
        .execute();

    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, UUID.randomUUID())
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.CUSTOMER_ID, customer)
        .set(PAYMENT.SALES_ORDER_ID, orderId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.STATUS, com.loai.inventory.repository.generated.enums.PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, now().minusHours(2))
        .execute();
  }

  /**
   * A RECEIVED, fully-unallocated payment not tied to any order — the orphan/overpayment source.
   */
  private UUID seedOrphanPayment(UUID org, UUID customer, String amount) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "ORPHAN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusHours(2))
        .execute();

    UUID paymentId = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, paymentId)
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.CUSTOMER_ID, customer)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.STATUS, com.loai.inventory.repository.generated.enums.PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, now().minusHours(2))
        .execute();
    return paymentId;
  }

  // query helpers

  private String refundStatus(UUID id) {
    return dsl.select(REFUND.STATUS)
        .from(REFUND)
        .where(REFUND.ID.eq(id))
        .fetchOne(REFUND.STATUS)
        .getLiteral();
  }

  private UUID refundTxnId(UUID id) {
    return dsl.select(REFUND.PAYMENT_TRANSACTION_ID)
        .from(REFUND)
        .where(REFUND.ID.eq(id))
        .fetchOne(REFUND.PAYMENT_TRANSACTION_ID);
  }

  private String creditNoteStatus(UUID id) {
    return dsl.select(CREDIT_NOTE.STATUS)
        .from(CREDIT_NOTE)
        .where(CREDIT_NOTE.ID.eq(id))
        .fetchOne(CREDIT_NOTE.STATUS)
        .getLiteral();
  }

  private String paymentStatus(UUID id) {
    return dsl.select(PAYMENT.STATUS)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.STATUS)
        .getLiteral();
  }

  private BigDecimal paymentUnallocated(UUID id) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
  }

  private BigDecimal paymentRefunded(UUID id) {
    return dsl.select(PAYMENT.REFUNDED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.REFUNDED_AMOUNT);
  }

  private String invoiceStatus(UUID id) {
    return dsl.select(SALES_INVOICE.STATUS)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(id))
        .fetchOne(SALES_INVOICE.STATUS)
        .getLiteral();
  }

  private BigDecimal invoicePaid(UUID id) {
    return dsl.select(SALES_INVOICE.PAID_AMOUNT)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(id))
        .fetchOne(SALES_INVOICE.PAID_AMOUNT);
  }

  private int refundAllocationCount(UUID refundId) {
    return dsl.fetchCount(
        dsl.selectFrom(REFUND_ALLOCATION).where(REFUND_ALLOCATION.REFUND_ID.eq(refundId)));
  }

  private BigDecimal refundAllocationSum(UUID refundId) {
    BigDecimal sum =
        dsl.select(DSL.sum(REFUND_ALLOCATION.AMOUNT))
            .from(REFUND_ALLOCATION)
            .where(REFUND_ALLOCATION.REFUND_ID.eq(refundId))
            .fetchOne(DSL.sum(REFUND_ALLOCATION.AMOUNT));
    return sum == null ? BigDecimal.ZERO : sum;
  }

  private PaymentDirection debitDirection(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.DIRECTION)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.DIRECTION);
  }

  private PaymentVerificationStatus debitVerification(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.VERIFICATION_STATUS);
  }

  private int debitTxnCount(UUID org) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION)
            .where(
                PAYMENT_TRANSACTION
                    .ORG_ID
                    .eq(org)
                    .and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.DEBIT))));
  }
}
