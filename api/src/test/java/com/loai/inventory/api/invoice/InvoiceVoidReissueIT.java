package com.loai.inventory.api.invoice;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CreditNoteReason;
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
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CreditNoteService.IssueCommand;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.ReissueLine;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.InvoiceService.Issued;
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
 * Integration coverage for the invoice void + reissue slice ({@code
 * stories/invoice_void_reissue.md}). Boots one PostgreSQL container, runs all Flyway migrations
 * (incl. the V35 partial unique index), and drives {@link InvoiceAdminService} against the real
 * jOOQ repository factories. The fixture is an ISSUED, <em>unpaid</em> invoice produced by the real
 * {@link FulfillmentService} deliver flow (no payment seeded → zero allocations → voidable).
 */
@Testcontainers
class InvoiceVoidReissueIT {

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
  static InvoiceAdminService invoiceAdminService;
  static SalesInvoiceRepositoryFactoryImpl invoiceRepoFactory;

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
    InvoiceService invoiceService =
        new InvoiceService(
            invoiceRepoFactory,
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
                new com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl()));
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
            invoiceRepoFactory,
            new PaymentAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            invoiceService);
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

  // void

  /** Void an ISSUED, unpaid, uncredited invoice → VOID with reason + voided_at recorded. */
  @Test
  void void_cancelsIssuedUnpaidInvoice() {
    Fixture f = deliverUnpaidInvoice("100.00");
    assertEquals("ISSUED", invoiceStatus(f.invoiceId));

    var view = invoiceAdminService.voidInvoice(f.org, f.invoiceId, "issued in error");

    assertEquals("VOID", view.invoice().getStatus().name());
    assertEquals("VOID", invoiceStatus(f.invoiceId));
    assertEquals("issued in error", invoiceVoidReason(f.invoiceId));
    assertNotNull(invoiceVoidedAt(f.invoiceId));
  }

  /** A blank reason is rejected before any state change. */
  @Test
  void void_requiresReason() {
    Fixture f = deliverUnpaidInvoice("100.00");
    assertThrows(
        ValidationException.class, () -> invoiceAdminService.voidInvoice(f.org, f.invoiceId, "  "));
    assertEquals("ISSUED", invoiceStatus(f.invoiceId));
  }

  /** A paid invoice (allocations exist) cannot be voided — use a credit note instead. */
  @Test
  void void_rejectedWhenInvoiceHasAllocations() {
    Fixture f = deliverPaidInvoice("100.00", "100.00");
    assertEquals("PAID", invoiceStatus(f.invoiceId));
    assertThrows(
        ConflictException.class,
        () -> invoiceAdminService.voidInvoice(f.org, f.invoiceId, "too late"));
    assertEquals("PAID", invoiceStatus(f.invoiceId));
  }

  /** An invoice with a live credit note cannot be voided — the trail must be reversed first. */
  @Test
  void void_rejectedWhenLiveCreditNoteExists() {
    Fixture f = deliverUnpaidInvoice("100.00");
    creditNoteService.issue(f.org, returnCommand(f.invoiceId, "40.00"), false);

    assertThrows(
        ConflictException.class, () -> invoiceAdminService.voidInvoice(f.org, f.invoiceId, "nope"));
    assertEquals("ISSUED", invoiceStatus(f.invoiceId));
  }

  /** Double void is rejected: a VOID invoice is no longer ISSUED. */
  @Test
  void void_rejectedWhenAlreadyVoid() {
    Fixture f = deliverUnpaidInvoice("100.00");
    invoiceAdminService.voidInvoice(f.org, f.invoiceId, "first");
    assertThrows(
        ConflictException.class,
        () -> invoiceAdminService.voidInvoice(f.org, f.invoiceId, "second"));
  }

  // reissue

  /**
   * Reissue voids the wrong invoice and issues a corrected one against the SAME fulfillment with a
   * fresh number — the partial unique index lets the two coexist.
   */
  @Test
  void reissue_voidsOldAndIssuesCorrectedAgainstSameFulfillment() {
    Fixture f = deliverUnpaidInvoice("100.00"); // qty 10 @ 10.00
    String oldNumber = invoiceNumber(f.invoiceId);

    // Corrected: a 10% discount that was missed → 10 @ 9.00 = 90.00.
    Issued issued =
        invoiceAdminService.reissue(
            f.org,
            f.invoiceId,
            "missed 10% discount",
            List.of(
                new ReissueLine(
                    f.productId,
                    "widget (corrected)",
                    10,
                    new BigDecimal("9.00"),
                    BigDecimal.ZERO)));

    UUID newId = issued.invoice().getId();

    // Old → VOID (with reason); new → ISSUED, different id + number, same fulfillment.
    assertEquals("VOID", invoiceStatus(f.invoiceId));
    assertEquals("missed 10% discount", invoiceVoidReason(f.invoiceId));
    assertNotEquals(f.invoiceId, newId);
    assertEquals("ISSUED", invoiceStatus(newId));
    assertNotEquals(oldNumber, invoiceNumber(newId));
    assertEquals(f.fulfillmentId, issued.invoice().getFulfillmentId());
    assertEquals(0, new BigDecimal("90.00").compareTo(issued.invoice().getGrandTotal()));

    // Exactly two rows for the fulfillment; exactly one is live, and it is the new invoice.
    assertEquals(2, invoiceCountForFulfillment(f.fulfillmentId));
    assertEquals(1, liveInvoiceCountForFulfillment(f.fulfillmentId));
    SalesInvoice live =
        invoiceRepoFactory.create(dsl).findByFulfillmentId(f.org, f.fulfillmentId).orElseThrow();
    assertEquals(newId, live.getId());
  }

  /** Reissue can be chained: each correction voids the prior live invoice — many VOIDs coexist. */
  @Test
  void reissue_canBeChained() {
    Fixture f = deliverUnpaidInvoice("100.00");

    UUID second =
        invoiceAdminService
            .reissue(
                f.org,
                f.invoiceId,
                "fix 1",
                List.of(
                    new ReissueLine(
                        f.productId, "v2", 10, new BigDecimal("9.00"), BigDecimal.ZERO)))
            .invoice()
            .getId();
    UUID third =
        invoiceAdminService
            .reissue(
                f.org,
                second,
                "fix 2",
                List.of(
                    new ReissueLine(
                        f.productId, "v3", 10, new BigDecimal("8.00"), BigDecimal.ZERO)))
            .invoice()
            .getId();

    assertEquals("VOID", invoiceStatus(f.invoiceId));
    assertEquals("VOID", invoiceStatus(second));
    assertEquals("ISSUED", invoiceStatus(third));
    assertEquals(3, invoiceCountForFulfillment(f.fulfillmentId));
    assertEquals(1, liveInvoiceCountForFulfillment(f.fulfillmentId));
  }

  /** Reissue requires at least one corrected line. */
  @Test
  void reissue_requiresLines() {
    Fixture f = deliverUnpaidInvoice("100.00");
    assertThrows(
        ValidationException.class,
        () -> invoiceAdminService.reissue(f.org, f.invoiceId, "oops", List.of()));
    assertEquals("ISSUED", invoiceStatus(f.invoiceId)); // untouched — guard ran before the void
  }

  /** Reissue is rejected on a paid invoice, same as void. */
  @Test
  void reissue_rejectedWhenInvoiceHasAllocations() {
    Fixture f = deliverPaidInvoice("100.00", "100.00");
    assertThrows(
        ConflictException.class,
        () ->
            invoiceAdminService.reissue(
                f.org,
                f.invoiceId,
                "too late",
                List.of(
                    new ReissueLine(
                        f.productId, "x", 1, new BigDecimal("1.00"), BigDecimal.ZERO))));
    assertEquals("PAID", invoiceStatus(f.invoiceId));
    assertEquals(1, invoiceCountForFulfillment(f.fulfillmentId)); // no replacement created
  }

  // get

  /** get returns the invoice with its lines. */
  @Test
  void get_returnsInvoiceWithLines() {
    Fixture f = deliverUnpaidInvoice("100.00");
    var view = invoiceAdminService.get(f.org, f.invoiceId);
    assertEquals(f.invoiceId, view.invoice().getId());
    assertEquals(1, view.lines().size());
    assertNull(view.invoice().getVoidedAt());
  }

  // fixtures & helpers

  private record Fixture(
      UUID org, UUID customer, UUID productId, UUID orderId, UUID fulfillmentId, UUID invoiceId) {}

  private IssueCommand returnCommand(UUID invoiceId, String total) {
    return new IssueCommand(
        invoiceId,
        CreditNoteReason.RETURN,
        "customer returned goods",
        List.of(
            new CreditNoteService.LineSpec(
                null, "returned item", 1, new BigDecimal(total), BigDecimal.ZERO)));
  }

  /** Deliver one ISSUED, unpaid invoice (no payment → zero allocations). */
  private Fixture deliverUnpaidInvoice(String grandTotal) {
    return deliver(grandTotal, null);
  }

  /** Deliver one fully-paid invoice (payment auto-allocated on delivery → PAID). */
  private Fixture deliverPaidInvoice(String grandTotal, String paymentAmount) {
    return deliver(grandTotal, paymentAmount);
  }

  private Fixture deliver(String grandTotal, String paymentAmount) {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Nadia", "nadia@acme.test");
    UUID product = createProduct(org, "SKU");
    int qty = new BigDecimal(grandTotal).divide(new BigDecimal("10.00")).intValue();
    createInventory(org, product, qty + 5, qty);

    UUID orderId = seedOrder(org, customer, product, qty, grandTotal, paymentAmount);
    UUID lineId =
        dsl.select(SALES_ORDER_LINE.ID)
            .from(SALES_ORDER_LINE)
            .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(orderId))
            .fetchAny(SALES_ORDER_LINE.ID);

    UUID fulfillmentId =
        fulfillmentService
            .create(org, orderId, List.of(new LineInput(lineId)), null, null, null, actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, fulfillmentId, actor);
    DeliveredView view = fulfillmentService.markDelivered(org, fulfillmentId, actor);
    return new Fixture(org, customer, product, orderId, fulfillmentId, view.invoice().getId());
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

  /** Seed a delivered-ready order. When {@code paymentAmount} is null, no payment is created. */
  private UUID seedOrder(
      UUID org, UUID customer, UUID product, int qty, String grandTotal, String paymentAmount) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    BigDecimal grand = new BigDecimal(grandTotal);
    BigDecimal prepaid = paymentAmount == null ? BigDecimal.ZERO : new BigDecimal(paymentAmount);
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

    if (paymentAmount != null) {
      seedPayment(org, customer, orderId, paymentAmount);
    }
    return orderId;
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

  // query helpers

  private String invoiceStatus(UUID id) {
    return dsl.select(SALES_INVOICE.STATUS)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(id))
        .fetchOne(SALES_INVOICE.STATUS)
        .getLiteral();
  }

  private String invoiceNumber(UUID id) {
    return dsl.select(SALES_INVOICE.INVOICE_NUMBER)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(id))
        .fetchOne(SALES_INVOICE.INVOICE_NUMBER);
  }

  private String invoiceVoidReason(UUID id) {
    return dsl.select(SALES_INVOICE.VOID_REASON)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(id))
        .fetchOne(SALES_INVOICE.VOID_REASON);
  }

  private OffsetDateTime invoiceVoidedAt(UUID id) {
    return dsl.select(SALES_INVOICE.VOIDED_AT)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(id))
        .fetchOne(SALES_INVOICE.VOIDED_AT);
  }

  private int invoiceCountForFulfillment(UUID fulfillmentId) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_INVOICE).where(SALES_INVOICE.FULFILLMENT_ID.eq(fulfillmentId)));
  }

  private int liveInvoiceCountForFulfillment(UUID fulfillmentId) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_INVOICE)
            .where(
                SALES_INVOICE
                    .FULFILLMENT_ID
                    .eq(fulfillmentId)
                    .and(
                        SALES_INVOICE.STATUS.ne(
                            com.loai.inventory.repository.generated.enums.InvoiceStatus.VOID))));
  }
}
