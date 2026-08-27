package com.loai.inventory.api.sale;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ApprovalRequiredException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.StockReason;
import com.loai.inventory.service.CounterReturnService;
import com.loai.inventory.service.CounterReturnService.LineInput;
import com.loai.inventory.service.CounterReturnService.RefundMode;
import com.loai.inventory.service.CounterReturnService.ReturnCommand;
import com.loai.inventory.service.CounterReturnService.Returnable;
import com.loai.inventory.service.CounterReturnService.Returned;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.DiscountInput;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
 * Integration coverage for the counter return ({@code stories/counter_return.md}, V89): the {@link
 * InStoreSaleIT} harness makes real counter sales, then {@link CounterReturnService} takes them
 * back — a RETURN credit note against the sale's invoice, the refund (EXECUTED for cash, PENDING
 * for a transfer) and the restock, in one transaction. Ledger assertions model {@code
 * CreditNoteRefundIT}.
 */
@Testcontainers
class CounterReturnIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService service;
  static CreditNoteService creditNoteService;
  static RefundService refundService;
  static CounterReturnService counterReturnService;

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

    ReservationService reservationService =
        new ReservationService(
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl());
    InvoiceService invoiceService =
        new InvoiceService(
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    refundService =
        new com.loai.inventory.service.RefundService(
            dsl,
            new com.loai.inventory.repository.RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl());
    FulfillmentService fulfillmentService =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            invoiceService,
            refundService,
            new com.loai.inventory.service.ReservationService(
                new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl()),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
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
    com.loai.inventory.service.MagicLinkService magicLink =
        new com.loai.inventory.service.MagicLinkService(
            dsl,
            new com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
            "http://localhost:8080",
            java.time.Duration.ofDays(30));
    service =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            new com.loai.inventory.service.NotificationService(
                dsl,
                new com.loai.inventory.repository.NotificationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.UserRepositoryFactoryImpl(),
                new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
                new com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl(),
                new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
                new com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl(),
                new com.loai.inventory.service.email.LoggingEmailSender(),
                magicLink,
                new com.loai.inventory.service.whatsapp.LoggingWhatsAppSender(),
                com.loai.inventory.service.NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS),
            magicLink,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.CouponService(
                dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    creditNoteService =
        new CreditNoteService(
            dsl,
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl());
    counterReturnService =
        new CounterReturnService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            creditNoteService,
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
        "TRUNCATE refund_allocation, refund, credit_note_line, credit_note,"
            + " credit_note_number_counter, payment_allocation, sales_invoice_line, sales_invoice,"
            + " payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, sales_order_line, sales_order, customer, product,"
            + " user_org_role, app_user, org, order_number_counter, invoice_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  // scenarios

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
  }

  private static ReturnCommand ret(UUID product, int qty) {
    return new ReturnCommand(List.of(new LineInput(product, qty)), true, null);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  /** A counter sale of {@code qty × product} with an optional PERCENT discount, by a manager. */
  private InStoreSale sell(
      UUID org, UUID manager, UUID product, int qty, PaymentProvider tender, String percentOff) {
    return service.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, qty)),
        List.of(
            new PaymentInput(tender, tender == PaymentProvider.CASH ? null : "IPN-" + key(), null)),
        percentOff == null
            ? null
            : new DiscountInput(CouponType.PERCENT, new BigDecimal(percentOff), null),
        null,
        actor(manager),
        key(),
        manager,
        true);
  }

  /** A split counter sale ({@code stories/split_tender.md}) of {@code qty × product}. */
  private InStoreSale sellWith(
      UUID org, UUID manager, UUID product, int qty, List<PaymentInput> tenders) {
    return service.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, qty)),
        tenders,
        null,
        null,
        actor(manager),
        key(),
        manager,
        true);
  }

  private static PaymentInput instaPay(String amount) {
    return new PaymentInput(
        PaymentProvider.INSTAPAY_IN_STORE, "IPN-" + key(), new BigDecimal(amount));
  }

  private static PaymentInput cash(String amount) {
    return new PaymentInput(PaymentProvider.CASH, null, new BigDecimal(amount));
  }

  private Returned doReturn(
      UUID org, UUID manager, UUID orderId, ReturnCommand cmd, String key, boolean ownerOrAdmin) {
    return counterReturnService.returnFromReceipt(
        org, orderId, cmd, key, actor(manager), manager, ownerOrAdmin);
  }

  /**
   * The headline: 3 × 100 at −10% (grand 270), all three back for cash. One POST → note 270.00
   * (gross 300, discount share 30, tax 0) SETTLED, refund EXECUTED CASH 270.00 with a VERIFIED
   * DEBIT cash transaction, payment REFUNDED, invoice still PAID with paid_amount untouched, three
   * units back on the shelf under RETURNED linked to the order, restocked_at set.
   */
  @Test
  void fullCashReturn_onDiscountedSale_creditsRefundsAndRestocks() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, PaymentProvider.CASH, "10");
    assertMoney("270.00", sale.order().getGrandTotal());
    assertEquals(7, stockQty(org, product));

    Returned r = doReturn(org, manager, sale.order().getId(), ret(product, 3), key(), true);

    CreditNote note = r.creditNote();
    assertEquals("SETTLED", note.getStatus().name());
    assertMoney("300.00", note.getSubtotal());
    assertMoney("0.00", note.getTaxTotal());
    assertMoney("30.00", note.getDiscountTotal());
    assertMoney("270.00", note.getTotal());
    assertEquals("RETURN", note.getReason().name());
    assertNotNull(note.getRestockedAt());
    assertEquals(1, r.lines().size());
    assertEquals(3, r.lines().get(0).getQuantity());
    assertMoney("100.00", r.lines().get(0).getUnitPrice());
    assertEquals(sale.invoice().getId(), note.getSalesInvoiceId());

    Refund refund = r.refund();
    assertEquals("EXECUTED", refund.getStatus().name());
    assertEquals(PaymentProvider.CASH, refund.getMethod());
    assertMoney("270.00", refund.getAmount());
    assertNotNull(refund.getPaymentTransactionId());
    assertNotNull(r.debit());
    assertEquals(refund.getPaymentTransactionId(), r.debit().getId());
    assertEquals(1, cashDebitTxnCount(org));

    // Payment fully refunded; the gross rule: the invoice stays PAID with paid_amount unchanged.
    assertEquals("REFUNDED", paymentStatus(sale.payment().getId()));
    assertMoney("270.00", paymentRefunded(sale.payment().getId()));
    assertEquals("PAID", invoiceStatus(sale.invoice().getId()));
    assertMoney("270.00", invoicePaid(sale.invoice().getId()));

    // Stock: +3 back to the pre-sale figure, one RETURNED ledger row carrying the order id.
    assertEquals(10, stockQty(org, product));
    assertEquals(1, returnedLogCount(org, product, sale.order().getId()));
    assertEquals(1, r.stock().size());
    assertEquals(3, r.stock().get(0).quantity());
    assertEquals(10, r.stock().get(0).stockAfter());
    assertEquals(false, r.replayed());

    // Persisted, not just echoed.
    assertMoney("30.00", creditNoteDiscount(note.getId()));
    assertNotNull(creditNoteRestockedAt(note.getId()));
  }

  /** 1 of 3 → 90.00 (share 10.00); then the other 2 → 180.00 (the exact remainder 20.00). */
  @Test
  void partialThenCompletingReturn_sharesTheDiscountToThePiastre() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, PaymentProvider.CASH, "10");

    Returned first = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    assertMoney("10.00", first.creditNote().getDiscountTotal());
    assertMoney("90.00", first.creditNote().getTotal());
    assertMoney("90.00", first.refund().getAmount());
    assertEquals("PARTIALLY_REFUNDED", paymentStatus(sale.payment().getId()));
    assertEquals(8, stockQty(org, product));

    Returnable preview = counterReturnService.returnable(org, sale.order().getId());
    assertEquals(RefundMode.IMMEDIATE_CASH, preview.refundMode());
    assertEquals(PaymentProvider.CASH, preview.tender());
    assertEquals(3, preview.lines().get(0).billed());
    assertEquals(1, preview.lines().get(0).returned());
    assertEquals(2, preview.lines().get(0).returnable());
    assertMoney("90.00", preview.lines().get(0).unitRefund());

    Returned second = doReturn(org, manager, sale.order().getId(), ret(product, 2), key(), true);
    assertMoney("20.00", second.creditNote().getDiscountTotal());
    assertMoney("180.00", second.creditNote().getTotal());
    assertEquals("REFUNDED", paymentStatus(sale.payment().getId()));
    assertMoney("270.00", paymentRefunded(sale.payment().getId()));
    assertEquals(10, stockQty(org, product));

    // Σ notes == the grand total, exactly.
    assertMoney("270.00", first.creditNote().getTotal().add(second.creditNote().getTotal()));
    assertEquals(
        0, counterReturnService.returnable(org, sale.order().getId()).lines().get(0).returnable());
  }

  /**
   * 3 × 33.33 at 7% off, one at a time: every share HALF_EVEN, the last the remainder, Σ = grand.
   */
  @Test
  void prorationRounding_everyShareHalfEven_lastTakesTheRemainder() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("33.33"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, PaymentProvider.CASH, "7");
    // subtotal 99.99, discount 7% = 6.9993 → 7.00, grand 92.99
    assertMoney("7.00", sale.order().getDiscountTotal());
    assertMoney("92.99", sale.order().getGrandTotal());

    Returned a = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    // 7.00 × 33.33 / 99.99 = 2.33339… → 2.33
    assertMoney("2.33", a.creditNote().getDiscountTotal());
    assertMoney("31.00", a.creditNote().getTotal());
    Returned b = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    assertMoney("2.33", b.creditNote().getDiscountTotal());
    Returned c = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    // The completing return takes what is left: 7.00 − 4.66 = 2.34.
    assertMoney("2.34", c.creditNote().getDiscountTotal());
    assertMoney("30.99", c.creditNote().getTotal());
    assertMoney(
        "92.99",
        a.creditNote().getTotal().add(b.creditNote().getTotal()).add(c.creditNote().getTotal()));
    assertEquals("REFUNDED", paymentStatus(sale.payment().getId()));
  }

  /** org tax_rate 0.14: the note's tax is the line's invoice tax; total = gross + tax − share. */
  @Test
  void taxedSale_noteCarriesTheLineTax() {
    UUID org = createOrg("acme", "0.14", null);
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 2, PaymentProvider.CASH, "10");
    // 200 + 28 tax − 20 = 208
    assertMoney("208.00", sale.order().getGrandTotal());

    Returned r = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    assertMoney("100.00", r.creditNote().getSubtotal());
    assertMoney("14.00", r.creditNote().getTaxTotal());
    assertMoney("10.00", r.creditNote().getDiscountTotal());
    assertMoney("104.00", r.creditNote().getTotal());
    assertMoney(
        "104.00",
        counterReturnService.returnable(org, sale.order().getId()).lines().get(0).unitRefund());
  }

  /**
   * 4 of 3 → 409; and after all 3 are back, one more → 409 naming returnable 0; nothing written.
   */
  @Test
  void overQuantity_is409_writesNothing() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, PaymentProvider.CASH, null);

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> doReturn(org, manager, sale.order().getId(), ret(product, 4), key(), true));
    assertTrue(e.getMessage().contains("returnable 3 of 3"), e.getMessage());
    assertEquals(0, tableCount(CREDIT_NOTE));
    assertEquals(0, tableCount(REFUND));
    assertEquals(7, stockQty(org, product));

    doReturn(org, manager, sale.order().getId(), ret(product, 3), key(), true);
    ConflictException again =
        assertThrows(
            ConflictException.class,
            () -> doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true));
    assertTrue(again.getMessage().contains("returnable 0 of 3"), again.getMessage());
    assertEquals(1, tableCount(CREDIT_NOTE));
    assertEquals(10, stockQty(org, product));
  }

  /**
   * An InstaPay sale: the note is ISSUED and the refund PENDING with method INSTAPAY_MANUAL (the
   * merchant sends the transfer, then executes from the queue), the restock done now; the existing
   * desk execution then settles it exactly as any credit-note refund.
   */
  @Test
  void instaPaySale_leavesAPendingTransfer_restocksNow_thenDeskExecutionSettles() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 2, PaymentProvider.INSTAPAY_IN_STORE, null);

    Returnable preview = counterReturnService.returnable(org, sale.order().getId());
    assertEquals(RefundMode.PENDING_TRANSFER, preview.refundMode());
    assertEquals(PaymentProvider.INSTAPAY_IN_STORE, preview.tender());

    Returned r = doReturn(org, manager, sale.order().getId(), ret(product, 2), key(), true);
    assertEquals("ISSUED", r.creditNote().getStatus().name());
    assertEquals("PENDING", r.refund().getStatus().name());
    assertEquals(PaymentProvider.INSTAPAY_MANUAL, r.refund().getMethod());
    assertMoney("200.00", r.refund().getAmount());
    assertNull(r.debit());
    assertEquals(0, cashDebitTxnCount(org));
    assertEquals("ALLOCATED", paymentStatus(sale.payment().getId()));
    assertEquals(10, stockQty(org, product), "goods are back regardless of the money");
    assertNotNull(r.creditNote().getRestockedAt());

    refundService.execute(org, r.refund().getId(), "IPN-BACK-1", manager);
    assertEquals("EXECUTED", refundStatus(r.refund().getId()));
    assertEquals("SETTLED", creditNoteStatus(r.creditNote().getId()));
    assertEquals("REFUNDED", paymentStatus(sale.payment().getId()));
  }

  // split tender (stories/split_tender.md): cash iff the note fits the drawer's headroom

  /**
   * InstaPay 400 + cash 100 on a 5 × 100 sale. The preview says {@code cash_refundable 100}, {@code
   * SPLIT}, both tenders in canonical order. A 300.00 return (3 units) does not fit → a PENDING
   * INSTAPAY_MANUAL transfer; a 100.00 return (1 unit) fits → CASH, EXECUTED now. The transfer's
   * PENDING refund never spent cash, so the headroom is still 100 for the second.
   */
  @Test
  void splitSale_cashIffTheNoteFitsTheHeadroom() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale =
        sellWith(org, manager, product, 5, List.of(instaPay("400.00"), cash("100.00")));

    assertTrue(sale.changeRefunds().isEmpty(), "an exact split");
    Returnable preview = counterReturnService.returnable(org, sale.order().getId());
    assertEquals(RefundMode.SPLIT, preview.refundMode());
    assertMoney("100.00", preview.cashRefundable());
    assertEquals(2, preview.tenders().size());
    assertEquals(PaymentProvider.INSTAPAY_IN_STORE, preview.tenders().get(0).provider());
    assertMoney("400.00", preview.tenders().get(0).amount());
    assertEquals(PaymentProvider.CASH, preview.tenders().get(1).provider());
    assertMoney("100.00", preview.tenders().get(1).amount());
    assertEquals(PaymentProvider.INSTAPAY_IN_STORE, preview.tender(), "first canonical tender");

    Returned big = doReturn(org, manager, sale.order().getId(), ret(product, 3), key(), true);
    assertEquals("PENDING", big.refund().getStatus().name());
    assertEquals(PaymentProvider.INSTAPAY_MANUAL, big.refund().getMethod());
    assertMoney("300.00", big.refund().getAmount());
    assertNull(big.debit());
    assertEquals(0, cashDebitTxnCount(org));
    assertMoney(
        "100.00", counterReturnService.returnable(org, sale.order().getId()).cashRefundable());

    Returned small = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    assertEquals("EXECUTED", small.refund().getStatus().name());
    assertEquals(PaymentProvider.CASH, small.refund().getMethod());
    assertMoney("100.00", small.refund().getAmount());
    assertNotNull(small.debit());
    assertEquals(1, cashDebitTxnCount(org));

    Returnable after = counterReturnService.returnable(org, sale.order().getId());
    assertMoney("0.00", after.cashRefundable());
    assertEquals(RefundMode.PENDING_TRANSFER, after.refundMode(), "the cash is spent");
    assertEquals(9, stockQty(org, product), "4 of the 5 units came back");
  }

  /**
   * Earlier returns spend the headroom: cash 450 + InstaPay 50; 400 back in cash first (400 ≤ 450),
   * then the preview says 50 and a further 100.00 return is a transfer (50 < 100).
   */
  @Test
  void splitSale_earlierCashReturnsSpendTheHeadroom() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale =
        sellWith(org, manager, product, 5, List.of(cash("450.00"), instaPay("50.00")));
    assertMoney(
        "450.00", counterReturnService.returnable(org, sale.order().getId()).cashRefundable());

    Returned first = doReturn(org, manager, sale.order().getId(), ret(product, 4), key(), true);
    assertEquals(PaymentProvider.CASH, first.refund().getMethod());
    assertEquals("EXECUTED", first.refund().getStatus().name());
    assertMoney("400.00", first.refund().getAmount());

    Returnable preview = counterReturnService.returnable(org, sale.order().getId());
    assertMoney("50.00", preview.cashRefundable());
    assertEquals(RefundMode.SPLIT, preview.refundMode());

    Returned second = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    assertEquals(PaymentProvider.INSTAPAY_MANUAL, second.refund().getMethod());
    assertEquals("PENDING", second.refund().getStatus().name());
    assertMoney("100.00", second.refund().getAmount());
    assertEquals(1, cashDebitTxnCount(org));
  }

  /**
   * The counter change spends it too: cash 150 + InstaPay 450 on 500 hands 100 back at the sale, so
   * only 50 of the 150 in notes is still in the drawer for this sale — a 100.00 return is a
   * transfer even though 150 crossed the counter.
   */
  @Test
  void splitSale_counterChangeSpendsTheHeadroom() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale =
        sellWith(org, manager, product, 5, List.of(cash("150.00"), instaPay("450.00")));
    assertMoney("100.00", sale.changeAmount());

    Returnable preview = counterReturnService.returnable(org, sale.order().getId());
    assertMoney("50.00", preview.cashRefundable());
    assertEquals(RefundMode.SPLIT, preview.refundMode());

    Returned r = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    assertEquals(PaymentProvider.INSTAPAY_MANUAL, r.refund().getMethod());
    assertEquals("PENDING", r.refund().getStatus().name());
    assertEquals(1, cashDebitTxnCount(org), "only the sale's change was ever a cash DEBIT");
  }

  /**
   * A CANCELLED cash refund never counts: a desk-created PENDING cash refund of 400 against a
   * desk-issued note takes the headroom from 450 to 50; cancelling it gives the 400 back, and the
   * next unit's 100.00 return is cash again.
   */
  @Test
  void splitSale_cancelledCashRefundGivesTheHeadroomBack() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale =
        sellWith(org, manager, product, 5, List.of(cash("450.00"), instaPay("50.00")));

    // The desk path: a RETURN note for 4 units, then a PENDING cash refund for it.
    CreditNoteService.Issued deskNote =
        creditNoteService.issue(
            org,
            new CreditNoteService.IssueCommand(
                sale.invoice().getId(),
                com.loai.inventory.domain.model.CreditNoteReason.RETURN,
                null,
                List.of(
                    new CreditNoteService.LineSpec(
                        product, "SKU1 widget", 4, new BigDecimal("100.00"), BigDecimal.ZERO))),
            true);
    Refund pendingCash =
        refundService.create(
            org,
            new RefundService.CreateCommand(
                deskNote.creditNote().getId(),
                null,
                new BigDecimal("400.00"),
                "EGP",
                PaymentProvider.CASH,
                "keyed at the desk"),
            true);
    assertEquals("PENDING", pendingCash.getStatus().name());
    assertMoney(
        "50.00", counterReturnService.returnable(org, sale.order().getId()).cashRefundable());

    refundService.cancel(org, pendingCash.getId(), "keyed wrong");
    Returnable restored = counterReturnService.returnable(org, sale.order().getId());
    assertMoney("450.00", restored.cashRefundable());
    assertEquals(
        RefundMode.IMMEDIATE_CASH, restored.refundMode(), "450 ≥ the 100 still creditable");

    Returned r = doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true);
    assertEquals(PaymentProvider.CASH, r.refund().getMethod());
    assertEquals("EXECUTED", r.refund().getStatus().name());
    assertMoney("100.00", r.refund().getAmount());
  }

  /** restock:false — money identical, no ledger row, stock unchanged, restocked_at NULL. */
  @Test
  void restockFalse_keepsTheGoodsOut_moneyIdentical() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, PaymentProvider.CASH, "10");

    Returned r =
        doReturn(
            org,
            manager,
            sale.order().getId(),
            new ReturnCommand(List.of(new LineInput(product, 1)), false, "  damaged  "),
            key(),
            true);
    assertMoney("90.00", r.creditNote().getTotal());
    assertEquals("EXECUTED", r.refund().getStatus().name());
    assertEquals("damaged", r.creditNote().getReasonNote());
    assertNull(r.creditNote().getRestockedAt());
    assertNull(creditNoteRestockedAt(r.creditNote().getId()));
    assertEquals(0, r.stock().size());
    assertEquals(7, stockQty(org, product));
    assertEquals(0, returnedLogCount(org, product, sale.order().getId()));
  }

  /**
   * An untracked product (its inventory row deleted since) with restock:true → 409, no money moved.
   */
  @Test
  void untrackedProduct_withRestock_is409_writesNothing() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 1, PaymentProvider.CASH, null);
    dsl.deleteFrom(INVENTORY).where(INVENTORY.PRODUCT_ID.eq(product)).execute();

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), true));
    assertTrue(e.getMessage().contains("restock:false"), e.getMessage());
    assertEquals(0, tableCount(CREDIT_NOTE));
    assertEquals(0, tableCount(REFUND));
    assertEquals("ALLOCATED", paymentStatus(sale.payment().getId()));

    Returned r =
        doReturn(
            org,
            manager,
            sale.order().getId(),
            new ReturnCommand(List.of(new LineInput(product, 1)), false, null),
            key(),
            true);
    assertEquals("EXECUTED", r.refund().getStatus().name());
  }

  /**
   * The existing OWNER gate, unchanged: threshold 100, a 270 return by a MANAGER → 403
   * APPROVAL_REQUIRED naming OWNER with nothing written (not even the restock); an OWNER (or ADMIN)
   * passes.
   */
  @Test
  void aboveThreshold_needsOwner_nothingWritten() {
    UUID org = createOrg("acme", null, "100.00");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, PaymentProvider.CASH, "10");

    ApprovalRequiredException e =
        assertThrows(
            ApprovalRequiredException.class,
            () -> doReturn(org, manager, sale.order().getId(), ret(product, 3), key(), false));
    assertEquals("OWNER", e.getRequiredRole());
    assertMoney("100.00", e.getThresholdAmount());
    assertMoney("270.00", e.getRequestedAmount());
    assertEquals(0, tableCount(CREDIT_NOTE));
    assertEquals(0, tableCount(REFUND));
    assertEquals(7, stockQty(org, product), "the restock rolled back with the refusal");
    assertEquals(0, returnedLogCount(org, product, sale.order().getId()));

    // Under the bar a MANAGER is fine; an OWNER clears any bar.
    assertMoney(
        "90.00",
        doReturn(org, manager, sale.order().getId(), ret(product, 1), key(), false)
            .creditNote()
            .getTotal());
    assertMoney(
        "180.00",
        doReturn(org, manager, sale.order().getId(), ret(product, 2), key(), true)
            .creditNote()
            .getTotal());
  }

  /**
   * An ONLINE order is not a counter sale → 409 naming the credit-note route; the preview agrees.
   */
  @Test
  void onlineOrder_is409() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    var placed =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(new OrderLineInput(product, 1)),
            key(),
            null,
            actor(manager));

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> counterReturnService.returnable(org, placed.order().getId()));
    assertTrue(e.getMessage().contains("POST /credit-notes"), e.getMessage());
    assertThrows(
        ConflictException.class,
        () -> doReturn(org, manager, placed.order().getId(), ret(product, 1), key(), true));
    assertEquals(0, tableCount(CREDIT_NOTE));
  }

  /** Same key twice → one note, one refund, one ledger row, the second call replayed with 200. */
  @Test
  void idempotency_replayReturnsThePriorNote_differentBodyIs409() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, PaymentProvider.CASH, "10");
    String key = key();

    Returned first = doReturn(org, manager, sale.order().getId(), ret(product, 1), key, true);
    Returned again = doReturn(org, manager, sale.order().getId(), ret(product, 1), key, true);
    assertEquals(true, again.replayed());
    assertEquals(first.creditNote().getId(), again.creditNote().getId());
    assertEquals(first.refund().getId(), again.refund().getId());
    assertEquals(first.debit().getId(), again.debit().getId());
    assertEquals(1, tableCount(CREDIT_NOTE));
    assertEquals(1, tableCount(REFUND));
    assertEquals(1, returnedLogCount(org, product, sale.order().getId()));
    assertEquals(8, stockQty(org, product));
    assertEquals(1, again.stock().size());
    assertEquals(8, again.stock().get(0).stockAfter());
    assertEquals(key, creditNoteIdempotencyKey(first.creditNote().getId()));

    // The same key with a different return is a 409, never a silent "here is last time's".
    assertThrows(
        ConflictException.class,
        () -> doReturn(org, manager, sale.order().getId(), ret(product, 2), key, true));
    assertThrows(
        ConflictException.class,
        () ->
            doReturn(
                org,
                manager,
                sale.order().getId(),
                new ReturnCommand(List.of(new LineInput(product, 1)), false, null),
                key,
                true));
    assertEquals(1, tableCount(CREDIT_NOTE));

    // No key at all is a 400 before anything is read.
    assertThrows(
        ValidationException.class,
        () -> doReturn(org, manager, sale.order().getId(), ret(product, 1), " ", true));
  }

  /** Shape 400s: empty lines, a product not on the invoice, quantity 0, reason > 500. */
  @Test
  void malformedReturn_is400() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    UUID other = createProduct(org, "SKU2", new BigDecimal("5.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 1, PaymentProvider.CASH, null);
    UUID orderId = sale.order().getId();

    assertThrows(
        ValidationException.class,
        () ->
            doReturn(org, manager, orderId, new ReturnCommand(List.of(), true, null), key(), true));
    assertThrows(
        ValidationException.class,
        () -> doReturn(org, manager, orderId, ret(product, 0), key(), true));
    assertThrows(
        ValidationException.class,
        () -> doReturn(org, manager, orderId, ret(other, 1), key(), true));
    assertThrows(
        ValidationException.class,
        () ->
            doReturn(
                org,
                manager,
                orderId,
                new ReturnCommand(List.of(new LineInput(product, 1)), true, "x".repeat(501)),
                key(),
                true));
    assertEquals(0, tableCount(CREDIT_NOTE));
    assertEquals(9, stockQty(org, product));
  }

  /**
   * A desk-issued note against the same invoice still issues with discount_total 0 (regression).
   */
  @Test
  void deskIssuedNote_stillIssuesWithZeroDiscount() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 1, PaymentProvider.CASH, null);

    CreditNoteService.Issued issued =
        creditNoteService.issue(
            org,
            new CreditNoteService.IssueCommand(
                sale.invoice().getId(),
                com.loai.inventory.domain.model.CreditNoteReason.GOODWILL,
                "sorry",
                List.of(
                    new CreditNoteService.LineSpec(
                        product, "goodwill", 1, new BigDecimal("20.00"), BigDecimal.ZERO))),
            true);
    assertMoney("0.00", issued.creditNote().getDiscountTotal());
    assertMoney("20.00", issued.creditNote().getTotal());
    assertNull(issued.creditNote().getIdempotencyKey());
    assertNull(issued.creditNote().getRestockedAt());
  }

  // seed helpers

  private static ActorContext actor(UUID userId) {
    return ActorContext.user(userId.toString());
  }

  private UUID createOrg(String slug) {
    return createOrg(slug, null, null);
  }

  private UUID createOrg(String slug, String taxRate, String refundThreshold) {
    UUID id = UUID.randomUUID();
    var insert =
        dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, slug).set(ORG.SLUG, slug + "-" + id);
    if (taxRate != null) {
      insert = insert.set(ORG.TAX_RATE, new BigDecimal(taxRate));
    }
    if (refundThreshold != null) {
      insert = insert.set(ORG.REFUND_APPROVAL_THRESHOLD, new BigDecimal(refundThreshold));
    }
    insert.execute();
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

  private UUID createProduct(UUID org, String sku, BigDecimal basePrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, basePrice)
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

  // query helpers

  private String creditNoteStatus(UUID id) {
    return dsl.select(CREDIT_NOTE.STATUS)
        .from(CREDIT_NOTE)
        .where(CREDIT_NOTE.ID.eq(id))
        .fetchOne(CREDIT_NOTE.STATUS)
        .getLiteral();
  }

  private BigDecimal creditNoteDiscount(UUID id) {
    return dsl.select(CREDIT_NOTE.DISCOUNT_TOTAL)
        .from(CREDIT_NOTE)
        .where(CREDIT_NOTE.ID.eq(id))
        .fetchOne(CREDIT_NOTE.DISCOUNT_TOTAL);
  }

  private java.time.OffsetDateTime creditNoteRestockedAt(UUID id) {
    return dsl.select(CREDIT_NOTE.RESTOCKED_AT)
        .from(CREDIT_NOTE)
        .where(CREDIT_NOTE.ID.eq(id))
        .fetchOne(CREDIT_NOTE.RESTOCKED_AT);
  }

  private String creditNoteIdempotencyKey(UUID id) {
    return dsl.select(CREDIT_NOTE.IDEMPOTENCY_KEY)
        .from(CREDIT_NOTE)
        .where(CREDIT_NOTE.ID.eq(id))
        .fetchOne(CREDIT_NOTE.IDEMPOTENCY_KEY);
  }

  private String refundStatus(UUID id) {
    return dsl.select(REFUND.STATUS)
        .from(REFUND)
        .where(REFUND.ID.eq(id))
        .fetchOne(REFUND.STATUS)
        .getLiteral();
  }

  private String paymentStatus(UUID id) {
    return dsl.select(PAYMENT.STATUS)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.STATUS)
        .getLiteral();
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

  /** RETURNED ledger rows for the product, linked to the order — the restock's detail. */
  private int returnedLogCount(UUID org, UUID product, UUID orderId) {
    return dsl.fetchCount(
        dsl.selectFrom(INVENTORY_LOG)
            .where(
                INVENTORY_LOG
                    .ORG_ID
                    .eq(org)
                    .and(INVENTORY_LOG.PRODUCT_ID.eq(product))
                    .and(INVENTORY_LOG.ORDER_ID.eq(orderId))
                    .and(INVENTORY_LOG.REASON.eq(StockReason.RETURNED))));
  }

  private int stockQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private int tableCount(org.jooq.Table<?> table) {
    return dsl.fetchCount(table);
  }

  /** VERIFIED cash DEBIT transactions in the org — the recorded counter-change hand-backs. */
  private int cashDebitTxnCount(UUID org) {
    var txn = com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
    return dsl.fetchCount(
        dsl.selectFrom(txn)
            .where(
                txn.ORG_ID
                    .eq(org)
                    .and(
                        txn.DIRECTION.eq(
                            com.loai.inventory.repository.generated.enums.PaymentDirection.DEBIT))
                    .and(
                        txn.PROVIDER.eq(
                            com.loai.inventory.repository.generated.enums.PaymentProvider.cash))));
  }
}
