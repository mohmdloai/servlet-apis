package com.loai.inventory.api.invoice;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.InvoiceStatus;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.ReissueLine;
import com.loai.inventory.service.InvoiceService;
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
 * Adversarial verification of the order→CLOSED roll-up precondition {@link
 * InvoiceService#allLiveInvoicesPaid} and its real driver {@link FulfillmentService#markDelivered →
 * maybeRollUpOrder}. The existing {@code InvoiceVoidReissueIT} only proves void/reissue mechanics
 * on the invoice rows; it never drives the order roll-up nor exercises the three rollup gaps:
 *
 * <ul>
 *   <li><b>(A)</b> a VOID sibling must NOT block CLOSE (the historic bug);
 *   <li><b>(B)</b> an all-VOID invoice set must NOT vacuously CLOSE;
 *   <li><b>(C)</b> an all-live-PAID set yields true / drives CLOSE.
 * </ul>
 *
 * <p>(A) and (C) are exercised through the <em>realistic</em> production path — a real {@link
 * FulfillmentService} delivery that triggers {@code maybeRollUpOrder} — and asserted on the order's
 * persisted {@code status}. (B) cannot be produced by the realistic path (the roll-up is only ever
 * evaluated on a delivery event that atomically issues a live invoice, so a delivered order can
 * never present only a VOID invoice at roll-up time), so it is verified by calling {@code
 * allLiveInvoicesPaid} directly against seeded all-VOID rows. (C) is also asserted directly for a
 * tight unit-level check alongside its realistic counterpart.
 */
@Testcontainers
class InvoiceRollupVerificationIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static FulfillmentService fulfillmentService;
  static InvoiceAdminService invoiceAdminService;
  static InvoiceService invoiceService;
  static SalesInvoiceRepositoryFactoryImpl invoiceRepoFactory;
  static SalesOrderRepositoryFactoryImpl orderRepoFactory;

  private final AtomicInteger seq = new AtomicInteger(1);
  private final ActorContext actor = ActorContext.user(UUID.randomUUID().toString());

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

    invoiceRepoFactory = new SalesInvoiceRepositoryFactoryImpl();
    orderRepoFactory = new SalesOrderRepositoryFactoryImpl();
    invoiceService =
        new InvoiceService(
            invoiceRepoFactory,
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    fulfillmentService =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            orderRepoFactory,
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            invoiceService);
    invoiceAdminService =
        new InvoiceAdminService(
            dsl,
            invoiceRepoFactory,
            new PaymentAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            orderRepoFactory,
            new CustomerRepositoryFactoryImpl(),
            invoiceService);
    // creditNoteService is wired only to mirror the production composition; unused here.
    new CreditNoteService(
        dsl,
        new CreditNoteRepositoryFactoryImpl(),
        new SalesInvoiceRepositoryFactoryImpl(),
        new RefundRepositoryFactoryImpl(),
        new OrgRepositoryFactoryImpl());
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
        "TRUNCATE credit_note_line, credit_note, payment_allocation, sales_invoice_line,"
            + " sales_invoice, payment, payment_transaction, fulfillment, fulfillment_line,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, org, invoice_number_counter, credit_note_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  // ════════════════════════════ (A) VOID does not block CLOSE ════════════════════════════

  /**
   * REALISTIC PATH driven entirely through the production {@link FulfillmentService} delivery
   * roll-up. A two-line order with NO prepayment at delivery #1, so invoice #1 is issued ISSUED
   * (unpaid) and is therefore genuinely VOID/REISSUE-eligible (a PAID invoice could never be
   * reissued — that is the production rule, proven by {@code InvoiceVoidReissueIT}). Sequence:
   *
   * <ol>
   *   <li>Deliver line #1 → invoice #1 ISSUED, unpaid (order PAID → FULFILLING). No CLOSE: line #2
   *       still undelivered.
   *   <li>Seed a 200.00 prepayment on the order (money arrives after the wrong invoice was cut).
   *   <li>VOID + REISSUE invoice #1: original → VOID, the replacement is issued through the very
   *       same {@code issueForFulfillment} collaborator and auto-allocates 100.00 of the
   *       now-present prepayment → PAID. This is the void+reissue→PAID arrangement the task asks
   *       for.
   *   <li>Deliver line #2 → invoice #2 issued, auto-allocates the remaining 100.00 → PAID. This is
   *       the delivery that fires {@code maybeRollUpOrder}.
   * </ol>
   *
   * At roll-up time the order carries 1 VOID + 2 live PAID invoices. If VOID were not excluded,
   * {@code allMatch(isPaid)} would fail on the VOID row and the order would be stuck FULFILLED
   * forever (the historic bug). The order must reach CLOSED.
   */
  @Test
  void voidSibling_doesNotBlockClose_realisticDeliveryRollup() {
    TwoLineFixture f = seedTwoLineOrderNoPrepay();

    // Deliver line #1 → invoice #1 ISSUED, unpaid. Order: PAID → FULFILLING (not yet all
    // delivered).
    UUID f1 = deliverOneLine(f.org, f.orderId, f.lineId1);
    UUID inv1 = liveInvoiceForFulfillment(f.org, f1);
    assertEquals(
        InvoiceStatus.ISSUED,
        statusOf(inv1),
        "invoice #1 issued with no prepayment → ISSUED/unpaid");
    assertEquals(
        OrderStatus.FULFILLING, orderStatus(f.orderId), "first delivery moves order to FULFILLING");

    // Money arrives now (200.00 covering both invoices), then we void+reissue the wrong invoice #1.
    seedPayment(f.org, f.customer, f.orderId, "200.00");
    UUID replacement =
        invoiceAdminService
            .reissue(
                f.org,
                inv1,
                "corrected line 1",
                List.of(
                    new ReissueLine(
                        f.productId,
                        "line1 corrected",
                        10,
                        new BigDecimal("10.00"),
                        BigDecimal.ZERO)))
            .invoice()
            .getId();
    assertNotEquals(inv1, replacement);
    assertEquals(InvoiceStatus.VOID, statusOf(inv1), "original invoice #1 must be VOID");
    assertEquals(
        InvoiceStatus.PAID,
        statusOf(replacement),
        "replacement auto-allocates the now-present prepayment → PAID");

    // Deliver line #2 → invoice #2 (PAID from remaining prepayment) → this delivery fires
    // maybeRollUpOrder.
    UUID f2 = deliverOneLine(f.org, f.orderId, f.lineId2);
    UUID inv2 = liveInvoiceForFulfillment(f.org, f2);
    assertEquals(
        InvoiceStatus.PAID, statusOf(inv2), "invoice #2 should be PAID from remaining prepayment");

    // The order now has 1 VOID + 2 live PAID invoices. VOID must be ignored → CLOSED.
    long voidCount = countInvoices(f.orderId, InvoiceStatus.VOID);
    long liveCount = countLiveInvoices(f.orderId);
    assertEquals(1, voidCount, "exactly one VOID sibling expected");
    assertEquals(2, liveCount, "exactly two live PAID invoices expected");
    assertEquals(
        OrderStatus.CLOSED,
        orderStatus(f.orderId),
        "VOID sibling must NOT block CLOSE — order must reach CLOSED");

    // Cross-check the precondition directly too.
    assertTrue(
        allLivePaid(f.org, f.orderId),
        "allLiveInvoicesPaid must be true with a VOID sibling present");
  }

  // ════════════════════════════ (B) all-VOID does NOT vacuously CLOSE ════════════════════

  /**
   * DIRECT PATH (the realistic path cannot produce an all-VOID order at roll-up time). Seed an
   * order whose only invoices are VOID. {@code allLiveInvoicesPaid} must return false — the
   * non-empty guard must stop {@code allMatch} from vacuously succeeding over an empty live set.
   */
  @Test
  void allVoidInvoices_doesNotVacuouslyClose() {
    UUID org = createOrg("voidonly");
    UUID customer = createCustomer(org, "Vera", "vera@void.test");
    UUID product = createProduct(org, "VSKU");
    UUID orderId = seedBareOrder(org, customer, product, OrderStatus.FULFILLING);

    // Two VOID invoices, no live invoice at all.
    seedInvoiceRow(org, customer, orderId, InvoiceStatus.VOID, "100.00", "0.00");
    seedInvoiceRow(org, customer, orderId, InvoiceStatus.VOID, "100.00", "0.00");

    boolean result = allLivePaid(org, orderId);
    assertFalse(
        result, "an all-VOID invoice set must NOT vacuously satisfy the CLOSE precondition");

    // And with zero invoices at all (empty set) → also false.
    UUID emptyOrder = seedBareOrder(org, customer, product, OrderStatus.FULFILLING);
    boolean emptyResult = allLivePaid(org, emptyOrder);
    assertFalse(emptyResult, "an order with no invoices must NOT satisfy the CLOSE precondition");
  }

  // ════════════════════════════ (C) all live PAID → true / CLOSE ═════════════════════════

  /** DIRECT PATH: a single live PAID invoice → allLiveInvoicesPaid true. */
  @Test
  void allLivePaid_directPrecondition_true() {
    UUID org = createOrg("paid");
    UUID customer = createCustomer(org, "Pat", "pat@paid.test");
    UUID product = createProduct(org, "PSKU");
    UUID orderId = seedBareOrder(org, customer, product, OrderStatus.FULFILLING);
    seedInvoiceRow(org, customer, orderId, InvoiceStatus.PAID, "100.00", "100.00");

    boolean result = allLivePaid(org, orderId);
    assertTrue(result, "a single live PAID invoice must satisfy the CLOSE precondition");

    // One PAID + one VOID → still true (VOID excluded).
    seedInvoiceRow(org, customer, orderId, InvoiceStatus.VOID, "50.00", "0.00");
    boolean withVoid = allLivePaid(org, orderId);
    assertTrue(
        withVoid, "a VOID alongside a live PAID invoice must still satisfy the precondition");

    // Add an ISSUED (unpaid) live invoice → now false (not every live invoice is PAID).
    seedInvoiceRow(org, customer, orderId, InvoiceStatus.ISSUED, "30.00", "0.00");
    boolean withUnpaid = allLivePaid(org, orderId);
    assertFalse(withUnpaid, "an unpaid live invoice must fail the precondition");
  }

  /**
   * REALISTIC PATH: a single-line fully-prepaid order delivered end-to-end. {@code markDelivered}
   * issues a PAID invoice and drives FULFILLING → FULFILLED → CLOSED with no VOID involved.
   */
  @Test
  void allLivePaid_realisticDeliveryRollup_closes() {
    TwoLineFixture f = seedSingleLinePrepaidOrder();
    UUID ful = deliverOneLine(f.org, f.orderId, f.lineId1);
    UUID inv = liveInvoiceForFulfillment(f.org, ful);
    assertEquals(InvoiceStatus.PAID, statusOf(inv));
    assertEquals(
        OrderStatus.CLOSED,
        orderStatus(f.orderId),
        "a fully-paid single-line order must CLOSE on delivery");
  }

  // ════════════════════════════ fixtures & helpers ════════════════════════════

  private record TwoLineFixture(
      UUID org, UUID customer, UUID productId, UUID orderId, UUID lineId1, UUID lineId2) {}

  /** Drive create→ship→markDelivered for one order line; returns the fulfillment id. */
  private UUID deliverOneLine(UUID org, UUID orderId, UUID orderLineId) {
    UUID fulfillmentId =
        fulfillmentService
            .create(org, orderId, List.of(new LineInput(orderLineId)), null, null, null, actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, fulfillmentId, actor);
    fulfillmentService.markDelivered(org, fulfillmentId, actor);
    return fulfillmentId;
  }

  /**
   * A PAID, two-line order (each line qty 10 @ 10.00 = 100.00) with NO prepayment seeded, and
   * stock/reservations per line. Status PAID so the first ship moves it to FULFILLING. The caller
   * seeds payment later (after delivery #1) to drive the void+reissue→PAID arrangement.
   */
  private TwoLineFixture seedTwoLineOrderNoPrepay() {
    UUID org = createOrg("two");
    UUID customer = createCustomer(org, "Tara", "tara@two.test");
    UUID product = createProduct(org, "TSKU");
    createInventory(org, product, 50, 20);

    UUID orderId = UUID.randomUUID();
    seedOrderHeader(
        org, customer, orderId, new BigDecimal("200.00"), BigDecimal.ZERO, OrderStatus.PAID);
    UUID line1 = seedOrderLine(org, orderId, product, 10);
    UUID line2 = seedOrderLine(org, orderId, product, 10);
    return new TwoLineFixture(org, customer, product, orderId, line1, line2);
  }

  /** A PAID single-line order (qty 10 @ 10.00 = 100.00) fully prepaid 100.00. */
  private TwoLineFixture seedSingleLinePrepaidOrder() {
    UUID org = createOrg("one");
    UUID customer = createCustomer(org, "Omar", "omar@one.test");
    UUID product = createProduct(org, "OSKU");
    createInventory(org, product, 50, 10);

    UUID orderId = UUID.randomUUID();
    seedOrderHeader(
        org,
        customer,
        orderId,
        new BigDecimal("100.00"),
        new BigDecimal("100.00"),
        OrderStatus.PAID);
    UUID line1 = seedOrderLine(org, orderId, product, 10);
    seedPayment(org, customer, orderId, "100.00");
    return new TwoLineFixture(org, customer, product, orderId, line1, null);
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

  private void seedOrderHeader(
      UUID org,
      UUID customer,
      UUID orderId,
      BigDecimal grand,
      BigDecimal prepaid,
      OrderStatus status) {
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.SUBTOTAL, grand)
        .set(SALES_ORDER.GRAND_TOTAL, grand)
        .set(SALES_ORDER.PREPAID_AMOUNT, prepaid)
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();
  }

  /** One order line (qty @ 10.00) plus an ACTIVE reservation for the same quantity. */
  private UUID seedOrderLine(UUID org, UUID orderId, UUID product, int qty) {
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

    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, UUID.randomUUID())
        .set(INVENTORY_RESERVATION.ORG_ID, org)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, product)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, qty)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .execute();
    return lineId;
  }

  /** A bare order header (no lines / payment) for direct-precondition tests. */
  private UUID seedBareOrder(UUID org, UUID customer, UUID product, OrderStatus status) {
    UUID orderId = UUID.randomUUID();
    seedOrderHeader(org, customer, orderId, new BigDecimal("100.00"), BigDecimal.ZERO, status);
    return orderId;
  }

  /**
   * Insert a sales_invoice row directly with a chosen status/paid_amount (no lines needed). Each
   * row gets its OWN real fulfillment row because {@code fulfillment_id} is NOT NULL +
   * FK-constrained and the partial unique index forbids two LIVE invoices sharing a fulfillment.
   * Returns the invoice id.
   */
  private UUID seedInvoiceRow(
      UUID org, UUID customer, UUID orderId, InvoiceStatus status, String grand, String paid) {
    UUID fulfillmentId = seedFulfillmentRow(org, orderId);
    UUID id = UUID.randomUUID();
    int year = now().getYear();
    String number = "INV-" + year + "-" + String.format("%04d", seq.getAndIncrement());
    var ins =
        dsl.insertInto(SALES_INVOICE)
            .set(SALES_INVOICE.ID, id)
            .set(SALES_INVOICE.ORG_ID, org)
            .set(SALES_INVOICE.CUSTOMER_ID, customer)
            .set(SALES_INVOICE.SALES_ORDER_ID, orderId)
            .set(SALES_INVOICE.FULFILLMENT_ID, fulfillmentId)
            .set(SALES_INVOICE.INVOICE_NUMBER, number)
            .set(SALES_INVOICE.STATUS, status)
            .set(SALES_INVOICE.SUBTOTAL, new BigDecimal(grand))
            .set(SALES_INVOICE.TAX_TOTAL, BigDecimal.ZERO)
            .set(SALES_INVOICE.DISCOUNT_TOTAL, BigDecimal.ZERO)
            .set(SALES_INVOICE.GRAND_TOTAL, new BigDecimal(grand))
            .set(SALES_INVOICE.PAID_AMOUNT, new BigDecimal(paid))
            .set(SALES_INVOICE.CURRENCY, "EGP")
            .set(SALES_INVOICE.CUSTOMER_NAME, "Snapshot")
            .set(SALES_INVOICE.ISSUED_AT, now());
    if (status == InvoiceStatus.VOID) {
      ins = ins.set(SALES_INVOICE.VOID_REASON, "seeded void").set(SALES_INVOICE.VOIDED_AT, now());
    }
    ins.execute();
    return id;
  }

  /** A minimal PENDING fulfillment row to satisfy the sales_invoice.fulfillment_id FK. */
  private UUID seedFulfillmentRow(UUID org, UUID orderId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.FULFILLMENT)
        .set(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ID, id)
        .set(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ORG_ID, org)
        .set(com.loai.inventory.repository.generated.Tables.FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(
            com.loai.inventory.repository.generated.Tables.FULFILLMENT.STATUS,
            com.loai.inventory.repository.generated.enums.FulfillmentStatus.PENDING)
        .execute();
    return id;
  }

  private void seedPayment(UUID org, UUID customer, UUID orderId, String amount) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION)
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.ID, txnId)
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.ORG_ID, org)
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.PROVIDER_REF,
            "IPN-" + seq.getAndIncrement())
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.DIRECTION,
            com.loai.inventory.repository.generated.enums.PaymentDirection.CREDIT)
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.AMOUNT,
            new BigDecimal(amount))
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.VERIFICATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.VERIFIED)
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION.OCCURRED_AT,
            now().minusHours(2))
        .execute();

    dsl.insertInto(com.loai.inventory.repository.generated.Tables.PAYMENT)
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT.ID, UUID.randomUUID())
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT.ORG_ID, org)
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT.CUSTOMER_ID, customer)
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT.SALES_ORDER_ID, orderId)
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(com.loai.inventory.repository.generated.Tables.PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT.UNALLOCATED_AMOUNT,
            new BigDecimal(amount))
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT.STATUS,
            com.loai.inventory.repository.generated.enums.PaymentStatus.RECEIVED)
        .set(
            com.loai.inventory.repository.generated.Tables.PAYMENT.RECEIVED_AT, now().minusHours(2))
        .execute();
  }

  // ─────────────── query helpers ───────────────

  /** Evaluate the CLOSE precondition inside a transaction (boxed to avoid overload ambiguity). */
  private boolean allLivePaid(UUID org, UUID orderId) {
    Boolean r =
        dsl.transactionResult(
            cfg ->
                Boolean.valueOf(invoiceService.allLiveInvoicesPaid(DSL.using(cfg), org, orderId)));
    return r.booleanValue();
  }

  private OrderStatus orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS);
  }

  private InvoiceStatus statusOf(UUID invoiceId) {
    return dsl.select(SALES_INVOICE.STATUS)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(invoiceId))
        .fetchOne(SALES_INVOICE.STATUS);
  }

  private UUID liveInvoiceForFulfillment(UUID org, UUID fulfillmentId) {
    SalesInvoice live =
        invoiceRepoFactory.create(dsl).findByFulfillmentId(org, fulfillmentId).orElseThrow();
    return live.getId();
  }

  private long countInvoices(UUID orderId, InvoiceStatus status) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_INVOICE)
            .where(SALES_INVOICE.SALES_ORDER_ID.eq(orderId).and(SALES_INVOICE.STATUS.eq(status))));
  }

  private long countLiveInvoices(UUID orderId) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_INVOICE)
            .where(
                SALES_INVOICE
                    .SALES_ORDER_ID
                    .eq(orderId)
                    .and(SALES_INVOICE.STATUS.ne(InvoiceStatus.VOID))));
  }
}
