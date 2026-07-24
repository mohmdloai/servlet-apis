package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_ALLOCATION;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CreditNoteReason;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
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
import com.loai.inventory.service.PaymentDisputeService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.RefundService.CreateCommand;
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
 * Integration coverage for the post-allocation dispute lifecycle (sys-analysis/outbound/payment.md
 * §Disputed; state-machines.md F). Boots one PostgreSQL container, runs Flyway, and drives {@link
 * PaymentDisputeService} against the real jOOQ repositories. An ALLOCATED payment — produced via
 * the real deliver flow — is the fixture every dispute scenario operates on.
 */
@Testcontainers
class PaymentDisputeIT {

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
  static PaymentDisputeService disputeService;

  private final AtomicInteger seq = new AtomicInteger(1);
  private final ActorContext actor = ActorContext.user(UUID.randomUUID().toString());

  /** A real app_user — payment_transaction.verified_by has an FK to it. Survives truncate. */
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
    disputeService =
        new PaymentDisputeService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl());

    actorId = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.APP_USER)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.ID, actorId)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.EMAIL, "dispute-admin@test")
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

  // lifecycle

  /** dispute records reason + timestamp and freezes the (ALLOCATED) payment as DISPUTED. */
  @Test
  void dispute_flagsAllocatedPayment_recordsReasonAndTimestamp() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    assertEquals("ALLOCATED", paymentStatus(f.paymentId));

    disputeService.dispute(f.org, f.paymentId, "customer claims unauthorized", actorId);

    assertEquals("DISPUTED", paymentStatus(f.paymentId));
    assertNotNull(disputedAt(f.paymentId));
    assertEquals("customer claims unauthorized", disputeReason(f.paymentId));
  }

  /** uphold returns a DISPUTED payment to ALLOCATED; audit fields are retained. */
  @Test
  void uphold_returnsDisputedPaymentToAllocated() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    disputeService.dispute(f.org, f.paymentId, "looks wrong", actorId);

    disputeService.uphold(f.org, f.paymentId, actorId);

    assertEquals("ALLOCATED", paymentStatus(f.paymentId));
    // disputed_at retained as the historical record of the resolved dispute.
    assertNotNull(disputedAt(f.paymentId));
  }

  /**
   * Refund resolution reuses the existing CreditNote + Refund machinery: a DISPUTE_RESOLUTION
   * credit note + executed refund drives the DISPUTED payment to REFUNDED.
   */
  @Test
  void refundResolution_viaCreditNoteAndRefund_movesDisputedPaymentToRefunded() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    disputeService.dispute(f.org, f.paymentId, "chargeback", actorId);
    assertEquals("DISPUTED", paymentStatus(f.paymentId));

    UUID cnId =
        creditNoteService
            .issue(f.org, disputeResolutionCommand(f.invoiceId, "30.00"), false)
            .creditNote()
            .getId();
    UUID refundId =
        refundService
            .create(
                f.org,
                new CreateCommand(
                    cnId,
                    null,
                    new BigDecimal("30.00"),
                    "EGP",
                    PaymentProvider.INSTAPAY_MANUAL,
                    null),
                false)
            .getId();
    refundService.execute(f.org, refundId, "IP-DISPUTE-1", actorId);

    // CreditNote-backed refund is allowed to drain the DISPUTED payment → REFUNDED.
    assertEquals("REFUNDED", paymentStatus(f.paymentId));
    assertEquals(0, new BigDecimal("30.00").compareTo(paymentRefunded(f.paymentId)));
    assertEquals("SETTLED", creditNoteStatus(cnId));
  }

  // guards

  /** A payment that isn't ALLOCATED can't be disputed (RECEIVED orphan → 409). */
  @Test
  void dispute_rejectsNonAllocatedPayment() {
    UUID org = createOrg("acme");
    UUID orphan = seedOrphanPayment(org, null, "50.00"); // RECEIVED
    assertThrows(
        ConflictException.class, () -> disputeService.dispute(org, orphan, "nope", actorId));
    assertEquals("RECEIVED", paymentStatus(orphan));
  }

  /** Disputing twice is rejected — the second call sees DISPUTED, not ALLOCATED. */
  @Test
  void dispute_isNotIdempotent_secondCallRejected() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    disputeService.dispute(f.org, f.paymentId, "first", actorId);
    assertThrows(
        ConflictException.class,
        () -> disputeService.dispute(f.org, f.paymentId, "second", actorId));
  }

  /** Upholding a payment that isn't DISPUTED is rejected. */
  @Test
  void uphold_rejectsNonDisputedPayment() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    assertThrows(ConflictException.class, () -> disputeService.uphold(f.org, f.paymentId, actorId));
    assertEquals("ALLOCATED", paymentStatus(f.paymentId));
  }

  /**
   * The direct-from-Payment refund path stays blocked for a DISPUTED payment: an overpayment's
   * unallocated excess cannot be drained while the payment is under dispute.
   */
  @Test
  void directRefund_ofDisputedPayment_isRejected() {
    // Overpaid: invoice 30, paid 130 → 100 unallocated, payment PARTIALLY_ALLOCATED.
    Fixture f = deliverPaidInvoice("30.00", "130.00");
    assertEquals("PARTIALLY_ALLOCATED", paymentStatus(f.paymentId));

    // Refund the excess so the payment becomes ALLOCATED (unallocated → 0), then dispute it.
    UUID excessRefund =
        refundService
            .create(
                f.org,
                new CreateCommand(
                    null, f.paymentId, new BigDecimal("100.00"), "EGP", PaymentProvider.CASH, null),
                false)
            .getId();
    refundService.execute(f.org, excessRefund, null, actorId);
    assertEquals("ALLOCATED", paymentStatus(f.paymentId));

    disputeService.dispute(f.org, f.paymentId, "disputed after overpay refund", actorId);

    // No unallocated balance left to direct-refund → ConflictException, payment stays DISPUTED.
    assertThrows(
        ConflictException.class,
        () ->
            refundService.create(
                f.org,
                new CreateCommand(
                    null, f.paymentId, new BigDecimal("1.00"), "EGP", PaymentProvider.CASH, null),
                false));
    assertEquals("DISPUTED", paymentStatus(f.paymentId));
  }

  /**
   * A disputed payment is refunded in steps when its money spans several credit notes (one credit
   * note credits one invoice). Each CreditNote-backed refund accrues {@code refunded_amount} but
   * the payment <b>stays frozen as DISPUTED</b> — it never enters PARTIALLY_REFUNDED — until the
   * cumulative refunds cover the whole amount, then → REFUNDED. Here two DISPUTE_RESOLUTION credit
   * notes (10 + 20) against the one invoice drive a 30 payment to REFUNDED across two executions.
   */
  @Test
  void disputeRefund_accruesAcrossCreditNotes_staysDisputedUntilFullThenRefunded() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    disputeService.dispute(f.org, f.paymentId, "chargeback", actorId);

    // First refund: 10 of 30. Payment stays DISPUTED, refunded accrues to 10.
    executeDisputeRefund(f, "10.00", "IP-DISPUTE-A");
    assertEquals("DISPUTED", paymentStatus(f.paymentId), "still frozen after a sub-total refund");
    assertEquals(0, new BigDecimal("10.00").compareTo(paymentRefunded(f.paymentId)));

    // Second refund: remaining 20 → cumulative 30 == amount → REFUNDED.
    executeDisputeRefund(f, "20.00", "IP-DISPUTE-B");
    assertEquals("REFUNDED", paymentStatus(f.paymentId));
    assertEquals(0, new BigDecimal("30.00").compareTo(paymentRefunded(f.paymentId)));
  }

  /**
   * The genuine multi-invoice shape Finding 1 is about: one online prepayment delivered as two
   * fulfillments is allocated across <b>two</b> invoices. Disputing it and refunding each invoice's
   * DISPUTE_RESOLUTION CreditNote separately keeps the payment frozen as DISPUTED (accruing {@code
   * refunded_amount}) until both are credited — then → REFUNDED. Distinct from the same-invoice
   * accumulation test above: here each refund unwinds a different PaymentAllocation.
   */
  @Test
  void disputeRefund_acrossTwoInvoices_staysDisputedUntilBothCreditedThenRefunded() {
    TwoInvoiceFixture f = deliverTwoInvoicesOnePayment("30.00", "20.00"); // one 50 payment, 30 + 20
    assertEquals("ALLOCATED", paymentStatus(f.paymentId));
    assertEquals(2, paymentAllocationCount(f.paymentId), "one payment spread across two invoices");

    disputeService.dispute(f.org, f.paymentId, "chargeback", actorId);

    // Credit + refund the first invoice (30): payment stays frozen, refunded accrues to 30.
    executeDisputeRefund(f.org, f.invoice1, "30.00", "IP-2INV-A");
    assertEquals(
        "DISPUTED", paymentStatus(f.paymentId), "still frozen after only one invoice credited");
    assertEquals(0, new BigDecimal("30.00").compareTo(paymentRefunded(f.paymentId)));

    // Credit + refund the second invoice (20): cumulative 50 == amount → REFUNDED.
    executeDisputeRefund(f.org, f.invoice2, "20.00", "IP-2INV-B");
    assertEquals("REFUNDED", paymentStatus(f.paymentId));
    assertEquals(0, new BigDecimal("50.00").compareTo(paymentRefunded(f.paymentId)));
  }

  /**
   * Once a dispute-resolution refund has executed (real money returned), the dispute can only
   * resolve forward to REFUNDED — upholding it back to ALLOCATED is refused.
   */
  @Test
  void uphold_afterDisputeRefundBegan_isRejected() {
    Fixture f = deliverPaidInvoice("30.00", "30.00");
    disputeService.dispute(f.org, f.paymentId, "chargeback", actorId);
    executeDisputeRefund(f, "10.00", "IP-DISPUTE-A"); // partial dispute refund begins

    assertThrows(ConflictException.class, () -> disputeService.uphold(f.org, f.paymentId, actorId));
    assertEquals("DISPUTED", paymentStatus(f.paymentId), "still DISPUTED; uphold did not apply");
  }

  /** Issue a DISPUTE_RESOLUTION credit note for {@code amount} and execute its refund. */
  private void executeDisputeRefund(Fixture f, String amount, String providerRef) {
    UUID cnId =
        creditNoteService
            .issue(f.org, disputeResolutionCommand(f.invoiceId, amount), false)
            .creditNote()
            .getId();
    UUID refundId =
        refundService
            .create(
                f.org,
                new CreateCommand(
                    cnId,
                    null,
                    new BigDecimal(amount),
                    "EGP",
                    PaymentProvider.INSTAPAY_MANUAL,
                    null),
                false)
            .getId();
    refundService.execute(f.org, refundId, providerRef, actorId);
  }

  /**
   * The detail read carries every invoice the payment funded — the dispute-resolution entry point:
   * one payment auto-allocated across two deliveries surfaces both invoices, each with its
   * identity, so the admin can pick which one to credit ({@code stories/money_reads.md}).
   */
  @Test
  void get_carriesAllocations_oneInvoicePerFundedDelivery() {
    TwoInvoiceFixture f = deliverTwoInvoicesOnePayment("30.00", "20.00");

    PaymentDisputeService.PaymentView view = disputeService.get(f.org, f.paymentId);

    assertEquals(f.paymentId, view.payment().getId());
    assertEquals(2, view.allocations().size());
    var byInvoice = new java.util.HashMap<UUID, PaymentDisputeService.AllocationView>();
    view.allocations().forEach(a -> byInvoice.put(a.allocation().getSalesInvoiceId(), a));
    assertEquals(
        0, new BigDecimal("30.00").compareTo(byInvoice.get(f.invoice1).allocation().getAmount()));
    assertEquals(
        0, new BigDecimal("20.00").compareTo(byInvoice.get(f.invoice2).allocation().getAmount()));
    for (var a : view.allocations()) {
      assertNotNull(a.invoice().getInvoiceNumber(), "allocation must name its invoice");
      // The allocation fully funded each invoice, so delivery marked them PAID.
      assertEquals("PAID", a.invoice().getStatus().name());
    }
  }

  /** A never-allocated payment reads back with an empty allocation list, not a null. */
  @Test
  void get_unallocatedPayment_hasNoAllocations() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Omar", "omar@acme.test");
    UUID paymentId = seedOrphanPayment(org, customer, "75.00");

    PaymentDisputeService.PaymentView view = disputeService.get(org, paymentId);

    assertEquals(paymentId, view.payment().getId());
    assertTrue(view.allocations().isEmpty());
  }

  /** Reading a missing payment is a 404. */
  @Test
  void get_missingPayment_is404() {
    UUID org = createOrg("acme");
    assertThrows(
        com.loai.inventory.common.exception.NotFoundException.class,
        () -> disputeService.get(org, UUID.randomUUID()));
  }

  // fixtures & helpers

  private record Fixture(UUID org, UUID customer, UUID invoiceId, UUID paymentId) {}

  private record TwoInvoiceFixture(
      UUID org, UUID customer, UUID invoice1, UUID invoice2, UUID paymentId) {}

  /**
   * Issue a DISPUTE_RESOLUTION credit note for {@code amount} against {@code invoiceId}, execute
   * its refund.
   */
  private void executeDisputeRefund(UUID org, UUID invoiceId, String amount, String providerRef) {
    UUID cnId =
        creditNoteService
            .issue(org, disputeResolutionCommand(invoiceId, amount), false)
            .creditNote()
            .getId();
    UUID refundId =
        refundService
            .create(
                org,
                new CreateCommand(
                    cnId,
                    null,
                    new BigDecimal(amount),
                    "EGP",
                    PaymentProvider.INSTAPAY_MANUAL,
                    null),
                false)
            .getId();
    refundService.execute(org, refundId, providerRef, actorId);
  }

  /**
   * One online order, two lines, a single prepayment covering both — delivered as two separate
   * fulfillments so the one payment is auto-allocated (FIFO at each delivery) across two invoices.
   * The genuine multi-invoice shape Finding 1 targets.
   */
  private TwoInvoiceFixture deliverTwoInvoicesOnePayment(String line1Total, String line2Total) {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Omar", "omar@acme.test");
    UUID product1 = createProduct(org, "SKU1");
    UUID product2 = createProduct(org, "SKU2");
    int qty1 = new BigDecimal(line1Total).divide(new BigDecimal("10.00")).intValue();
    int qty2 = new BigDecimal(line2Total).divide(new BigDecimal("10.00")).intValue();
    createInventory(org, product1, qty1 + 5, qty1);
    createInventory(org, product2, qty2 + 5, qty2);

    BigDecimal grand = new BigDecimal(line1Total).add(new BigDecimal(line2Total));
    UUID orderId = UUID.randomUUID();
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PAID)
        .set(SALES_ORDER.SUBTOTAL, grand)
        .set(SALES_ORDER.GRAND_TOTAL, grand)
        .set(SALES_ORDER.PREPAID_AMOUNT, grand)
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();

    UUID line1 = seedOrderLine(orderId, org, product1, qty1);
    UUID line2 = seedOrderLine(orderId, org, product2, qty2);
    seedPayment(org, customer, orderId, grand.toPlainString());

    UUID f1 =
        fulfillmentService
            .create(org, orderId, List.of(new LineInput(line1)), null, null, null, actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, f1, actor);
    UUID invoice1 = fulfillmentService.markDelivered(org, f1, actor).invoice().getId();

    UUID f2 =
        fulfillmentService
            .create(org, orderId, List.of(new LineInput(line2)), null, null, null, actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, f2, actor);
    UUID invoice2 = fulfillmentService.markDelivered(org, f2, actor).invoice().getId();

    UUID paymentId =
        dsl.select(PAYMENT.ID)
            .from(PAYMENT)
            .where(PAYMENT.SALES_ORDER_ID.eq(orderId))
            .fetchAny(PAYMENT.ID);
    return new TwoInvoiceFixture(org, customer, invoice1, invoice2, paymentId);
  }

  private UUID seedOrderLine(UUID orderId, UUID org, UUID product, int qty) {
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
    return lineId;
  }

  private IssueCommand disputeResolutionCommand(UUID invoiceId, String total) {
    return new IssueCommand(
        invoiceId,
        CreditNoteReason.DISPUTE_RESOLUTION,
        "dispute resolved in customer's favour",
        List.of(
            new CreditNoteService.LineSpec(
                null, "disputed item", 1, new BigDecimal(total), BigDecimal.ZERO)));
  }

  private Fixture deliverPaidInvoice(String grandTotal, String paymentAmount) {
    UUID org = createOrg("acme");
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

    UUID ful =
        fulfillmentService
            .create(org, orderId, List.of(new LineInput(lineId)), null, null, null, actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, ful, actor);
    DeliveredView view = fulfillmentService.markDelivered(org, ful, actor);

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

  private String paymentStatus(UUID id) {
    return dsl.select(PAYMENT.STATUS)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.STATUS)
        .getLiteral();
  }

  private OffsetDateTime disputedAt(UUID id) {
    return dsl.select(PAYMENT.DISPUTED_AT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.DISPUTED_AT);
  }

  private String disputeReason(UUID id) {
    return dsl.select(PAYMENT.DISPUTE_REASON)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.DISPUTE_REASON);
  }

  private BigDecimal paymentRefunded(UUID id) {
    return dsl.select(PAYMENT.REFUNDED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.REFUNDED_AMOUNT);
  }

  private int paymentAllocationCount(UUID paymentId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_ALLOCATION).where(PAYMENT_ALLOCATION.PAYMENT_ID.eq(paymentId)));
  }

  private String creditNoteStatus(UUID id) {
    return dsl.select(com.loai.inventory.repository.generated.Tables.CREDIT_NOTE.STATUS)
        .from(com.loai.inventory.repository.generated.Tables.CREDIT_NOTE)
        .where(com.loai.inventory.repository.generated.Tables.CREDIT_NOTE.ID.eq(id))
        .fetchOne(com.loai.inventory.repository.generated.Tables.CREDIT_NOTE.STATUS)
        .getLiteral();
  }
}
