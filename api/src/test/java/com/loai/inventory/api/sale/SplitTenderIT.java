package com.loai.inventory.api.sale;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.ORG_MILESTONE;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_ALLOCATION;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentTransaction;
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
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.DiscountInput;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.SalesOrderService.TenderPayment;
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
 * Integration coverage for the split tender ({@code stories/split_tender.md}, Loyverse gap #4): the
 * {@link InStoreSaleIT} harness, sales settled by more than one tender. Headline properties: one
 * transaction + one payment per tender, allocated FIFO in the canonical order (InstaPay first, the
 * folded cash tender last) against the one invoice; the overpay residue handed back as EXECUTED
 * cash change off whichever payment(s) hold it; coverage judged on the sum; every single-tender
 * behaviour untouched.
 */
@Testcontainers
class SplitTenderIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService service;

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
    com.loai.inventory.service.RefundService refundService =
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
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, sales_order_line, sales_order, customer, product,"
            + " user_org_role, app_user, org, order_number_counter, invoice_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  // scenarios

  private static final PaymentProvider IP = PaymentProvider.INSTAPAY_IN_STORE;
  private static final PaymentProvider CASH = PaymentProvider.CASH;

  private static PaymentInput instaPay(String amount, String ref) {
    return new PaymentInput(IP, ref, new BigDecimal(amount));
  }

  private static PaymentInput cash(String amount) {
    return new PaymentInput(CASH, null, new BigDecimal(amount));
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
  }

  /** A 5 × 100.00 = 500.00 counter sale settled by {@code tenders}, keyed under {@code idem}. */
  private InStoreSale sell(
      UUID org, UUID staff, UUID product, List<PaymentInput> tenders, String idem) {
    return sell(org, staff, product, tenders, idem, null);
  }

  private InStoreSale sell(
      UUID org,
      UUID staff,
      UUID product,
      List<PaymentInput> tenders,
      String idem,
      DiscountInput discount) {
    return service.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, 5)),
        tenders,
        discount,
        null,
        actor(staff),
        idem,
        staff,
        discount != null);
  }

  /**
   * The headline: InstaPay 300 + cash 200 on a 500.00 ticket. Two VERIFIED + MATCHED CREDIT
   * transactions (providers as sent, the InstaPay one with its reference), two ALLOCATED payments
   * with nothing left over, two allocations that sum to the invoice grand, the invoice PAID at 500,
   * the order CLOSED with prepaid 500, stock moved once, FIRST_PAYMENT stamped once — and the
   * sale's {@code payments()} in canonical order with no change.
   */
  @Test
  void exactSplit_twoTransactionsTwoPaymentsOneInvoice() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    InStoreSale sale =
        sell(org, staff, product, List.of(instaPay("300.00", "IPN-77812"), cash("200.00")), key());

    assertEquals(2, sale.payments().size());
    TenderPayment first = sale.payments().get(0);
    TenderPayment second = sale.payments().get(1);
    assertEquals(IP, first.transaction().getProvider());
    assertEquals("IPN-77812", first.transaction().getProviderRef());
    assertEquals(CASH, second.transaction().getProvider());
    assertEquals("CASH-" + sale.order().getIdempotencyKey(), second.transaction().getProviderRef());
    for (TenderPayment tp : sale.payments()) {
      PaymentTransaction txn = tp.transaction();
      assertEquals("CREDIT", txn.getDirection().name());
      assertEquals("VERIFIED", txn.getVerificationStatus().name());
      assertEquals("MATCHED", txn.getReconciliationStatus().name());
      assertEquals("ALLOCATED", tp.payment().getStatus().name());
      assertMoney("0.00", tp.payment().getUnallocatedAmount());
      assertMoney("0.00", paymentUnallocated(tp.payment().getId()));
    }
    assertMoney("300.00", first.payment().getAmount());
    assertMoney("200.00", second.payment().getAmount());
    assertEquals(first.payment().getId(), sale.payment().getId(), "compat payment() = first");
    assertTrue(
        first.payment().getReceivedAt().isBefore(second.payment().getReceivedAt()),
        "received_at strictly increasing in canonical order");

    assertEquals(2, sale.allocations().size());
    assertMoney("500.00", allocatedTotal(sale.invoice().getId()));
    assertEquals("PAID", sale.invoice().getStatus().name());
    assertMoney("500.00", sale.invoice().getPaidAmount());
    assertEquals("CLOSED", sale.order().getStatus().name());
    assertMoney("500.00", sale.order().getPrepaidAmount());

    assertTrue(sale.changeRefunds().isEmpty());
    assertNull(sale.changeRefund());
    assertMoney("0.00", sale.changeAmount());
    assertEquals(0, cashDebitTxnCount(org));

    assertEquals(5, stockQty(org, product));
    assertEquals(1, soldLogCount(org, product));
    assertEquals(1, milestoneCount(org, "FIRST_PAYMENT"), "N tenders stamp FIRST_PAYMENT once");
    assertEquals(2, tableCount(PAYMENT));
    assertEquals(2, tableCount(PAYMENT_TRANSACTION));
  }

  /**
   * Overpaid on the notes: InstaPay 450 + cash 100 on 500. The residue sits on the cash payment
   * (canonical order), so there is ONE change refund — CASH 50, EXECUTED, with its DEBIT cash
   * transaction — drawn from the cash payment; the transfer's payment is untouched.
   */
  @Test
  void overpaidOnTheCashTender_oneChangeRefundOffTheCashPayment() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    InStoreSale sale =
        sell(org, staff, product, List.of(instaPay("450.00", "IPN-1"), cash("100.00")), key());

    TenderPayment transfer = sale.payments().get(0);
    TenderPayment notes = sale.payments().get(1);
    assertEquals(IP, transfer.transaction().getProvider());
    assertEquals(CASH, notes.transaction().getProvider());

    assertEquals(1, sale.changeRefunds().size());
    Refund change = sale.changeRefund();
    assertEquals("EXECUTED", change.getStatus().name());
    assertEquals(CASH, change.getMethod());
    assertMoney("50.00", change.getAmount());
    assertEquals(notes.payment().getId(), change.getPaymentId(), "change is a draw on the cash");
    assertNotNull(change.getPaymentTransactionId());
    assertMoney("50.00", sale.changeAmount());
    assertEquals(1, cashDebitTxnCount(org));

    assertMoney("50.00", paymentRefunded(notes.payment().getId()));
    assertMoney("0.00", paymentUnallocated(notes.payment().getId()));
    assertEquals("ALLOCATED", notes.payment().getStatus().name());
    assertMoney("0.00", paymentRefunded(transfer.payment().getId()));
    assertMoney("0.00", paymentUnallocated(transfer.payment().getId()));

    // Net money attached to the order is the grand; the invoice never saw the excess.
    assertMoney("500.00", sale.order().getPrepaidAmount());
    assertMoney("500.00", sale.invoice().getPaidAmount());
  }

  /**
   * The transfer itself overpaid: InstaPay 520 + cash 30 on 500. FIFO consumes 500 off the transfer
   * and never touches the cash, so BOTH payments hold a residue → two change refunds, 20 off the
   * transfer's payment and 30 off the cash, both CASH, summing to 50.
   */
  @Test
  void overpaidOnTheTransfer_twoChangeRefunds_bothCash() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    InStoreSale sale =
        sell(org, staff, product, List.of(instaPay("520.00", "IPN-1"), cash("30.00")), key());

    TenderPayment transfer = sale.payments().get(0);
    TenderPayment notes = sale.payments().get(1);
    assertEquals(2, sale.changeRefunds().size());
    Refund offTransfer = sale.changeRefunds().get(0);
    Refund offCash = sale.changeRefunds().get(1);
    assertEquals(transfer.payment().getId(), offTransfer.getPaymentId());
    assertMoney("20.00", offTransfer.getAmount());
    assertEquals(notes.payment().getId(), offCash.getPaymentId());
    assertMoney("30.00", offCash.getAmount());
    for (Refund r : sale.changeRefunds()) {
      assertEquals(CASH, r.getMethod());
      assertEquals("EXECUTED", r.getStatus().name());
    }
    assertMoney("50.00", sale.changeAmount());
    assertEquals(2, cashDebitTxnCount(org));

    // One allocation (the transfer covered everything); the cash payment was never consumed by
    // the allocator and is drained wholly by its change refund — so it ends REFUNDED (a payment
    // that funded nothing and went entirely back), the transfer ALLOCATED; neither holds a residue.
    assertEquals(1, sale.allocations().size());
    assertMoney("20.00", paymentRefunded(transfer.payment().getId()));
    assertMoney("30.00", paymentRefunded(notes.payment().getId()));
    assertEquals("ALLOCATED", transfer.payment().getStatus().name());
    assertEquals("REFUNDED", notes.payment().getStatus().name());
    for (TenderPayment tp : sale.payments()) {
      assertMoney("0.00", paymentUnallocated(tp.payment().getId()));
    }
    assertMoney("500.00", sale.order().getPrepaidAmount());
  }

  /**
   * Canonical order beats request order: the cashier keyed the cash first, the ledger still puts
   * the InstaPay payment first and the residue on the cash — the same ledger as the InstaPay-first
   * body.
   */
  @Test
  void canonicalOrder_beatsRequestOrder() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    InStoreSale sale =
        sell(org, staff, product, List.of(cash("100.00"), instaPay("450.00", "IPN-1")), key());

    assertEquals(IP, sale.payments().get(0).transaction().getProvider());
    assertEquals(CASH, sale.payments().get(1).transaction().getProvider());
    assertTrue(
        sale.payments()
            .get(0)
            .payment()
            .getReceivedAt()
            .isBefore(sale.payments().get(1).payment().getReceivedAt()));
    assertEquals(1, sale.changeRefunds().size());
    assertEquals(sale.payments().get(1).payment().getId(), sale.changeRefund().getPaymentId());
    assertMoney("50.00", sale.changeAmount());
  }

  /** Short on the sum: 450 + 40 on 500 → the existing "underpaid" rejection, nothing written. */
  @Test
  void short_isUnderpaid_nothingWritten() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                sell(
                    org,
                    staff,
                    product,
                    List.of(instaPay("450.00", "IPN-1"), cash("40.00")),
                    key()));
    assertTrue(e.getMessage().contains("underpaid"), e.getMessage());
    assertEquals(0, tableCount(SALES_ORDER));
    assertEquals(0, tableCount(PAYMENT));
    assertEquals(0, tableCount(PAYMENT_TRANSACTION));
    assertEquals(10, stockQty(org, product));
  }

  /**
   * Coverage is judged against the DISCOUNTED grand (V88): 10% off 500 = 450, so 400 + 40 is short
   * and 400 + 50 is exact — the sum check runs after the discount is applied.
   */
  @Test
  void short_isJudgedAgainstTheDiscountedGrand() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);
    DiscountInput tenOff = new DiscountInput(CouponType.PERCENT, new BigDecimal("10"), null);

    assertThrows(
        ValidationException.class,
        () ->
            sell(
                org,
                manager,
                product,
                List.of(instaPay("400.00", "IPN-1"), cash("40.00")),
                key(),
                tenOff));
    assertEquals(0, tableCount(SALES_ORDER));

    InStoreSale sale =
        sell(
            org,
            manager,
            product,
            List.of(instaPay("400.00", "IPN-2"), cash("50.00")),
            key(),
            tenOff);
    assertMoney("450.00", sale.order().getGrandTotal());
    assertMoney("450.00", sale.invoice().getPaidAmount());
    assertTrue(sale.changeRefunds().isEmpty());
  }

  /**
   * Rule 3: two cash amounts at one counter moment are one drawer event — folded into ONE cash
   * payment of their sum with the ordinary {@code CASH-<idem>} ref. A lone refless InstaPay tender
   * in a split keeps today's {@code INSTAPAY_IN_STORE-<idem>} (no ordinal — nothing to collide).
   */
  @Test
  void duplicateCash_isFoldedIntoOnePayment() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);
    String idem = key();

    InStoreSale sale =
        sell(
            org,
            staff,
            product,
            List.of(cash("100.00"), cash("200.00"), instaPay("200.00", null)),
            idem);

    assertEquals(2, sale.payments().size());
    TenderPayment transfer = sale.payments().get(0);
    TenderPayment notes = sale.payments().get(1);
    assertEquals(IP, transfer.transaction().getProvider());
    assertEquals("INSTAPAY_IN_STORE-" + idem, transfer.transaction().getProviderRef());
    assertEquals(CASH, notes.transaction().getProvider());
    assertMoney("300.00", notes.payment().getAmount());
    assertEquals("CASH-" + idem, notes.transaction().getProviderRef());
    assertEquals(2, tableCount(PAYMENT_TRANSACTION));
    assertTrue(sale.changeRefunds().isEmpty());
  }

  /**
   * Two refless InstaPay tenders get ordinal-suffixed synthesised refs so the global {@code
   * (provider, provider_ref)} UNIQUE does not collide inside one sale; a fifth tender is refused by
   * the cap before anything is written.
   */
  @Test
  void reflessInstaPayTwice_getsOrdinals_andTheCapIsFour() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 20, 0);
    String idem = key();

    InStoreSale sale =
        sell(
            org, staff, product, List.of(instaPay("300.00", null), instaPay("200.00", null)), idem);
    assertEquals(
        "INSTAPAY_IN_STORE-" + idem + "-1", sale.payments().get(0).transaction().getProviderRef());
    assertEquals(
        "INSTAPAY_IN_STORE-" + idem + "-2", sale.payments().get(1).transaction().getProviderRef());

    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                sell(
                    org,
                    staff,
                    product,
                    List.of(
                        instaPay("100.00", "A"),
                        instaPay("100.00", "B"),
                        instaPay("100.00", "C"),
                        instaPay("100.00", "D"),
                        cash("100.00")),
                    key()));
    assertTrue(e.getMessage().contains("at most 4"), e.getMessage());
    assertEquals(1, tableCount(SALES_ORDER));
    assertEquals(15, stockQty(org, product));
  }

  /**
   * Both double-submit barriers still fire on a split: the same body + key twice → 409 on the order
   * barrier; a DIFFERENT key that reuses the InstaPay reference → 409 on the transaction barrier,
   * with nothing of the second attempt written.
   */
  @Test
  void retry_sameKeyIs409_reusedInstaPayRefIs409_nothingWritten() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 20, 0);
    String idem = key();
    List<PaymentInput> body = List.of(instaPay("450.00", "IPN-SAME"), cash("50.00"));

    InStoreSale first = sell(org, staff, product, body, idem);
    assertEquals("CLOSED", first.order().getStatus().name());

    assertThrows(ConflictException.class, () -> sell(org, staff, product, body, idem));
    assertThrows(ConflictException.class, () -> sell(org, staff, product, body, key()));

    assertEquals(1, tableCount(SALES_ORDER));
    assertEquals(2, tableCount(PAYMENT));
    assertEquals(2, tableCount(PAYMENT_TRANSACTION));
    assertEquals(15, stockQty(org, product));
  }

  /** Shape 400s name the offending element; every split amount is required; INSTAPAY_MANUAL out. */
  @Test
  void malformedSplit_is400_namingTheElement() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    ValidationException noAmount =
        assertThrows(
            ValidationException.class,
            () ->
                sell(
                    org,
                    staff,
                    product,
                    List.of(instaPay("450.00", "IPN-1"), new PaymentInput(CASH, null, null)),
                    key()));
    assertTrue(noAmount.getMessage().startsWith("payments[1].amount"), noAmount.getMessage());

    ValidationException negative =
        assertThrows(
            ValidationException.class,
            () ->
                sell(org, staff, product, List.of(cash("-1.00"), instaPay("501.00", "X")), key()));
    assertTrue(negative.getMessage().startsWith("payments[0].amount"), negative.getMessage());

    assertThrows(
        ValidationException.class,
        () ->
            sell(
                org,
                staff,
                product,
                List.of(
                    new PaymentInput(PaymentProvider.INSTAPAY_MANUAL, "X", new BigDecimal("400")),
                    cash("100.00")),
                key()));
    assertThrows(ValidationException.class, () -> sell(org, staff, product, List.of(), key()));
    assertEquals(0, tableCount(SALES_ORDER));
  }

  // seed helpers

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static ActorContext actor(UUID userId) {
    return ActorContext.user(userId.toString());
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

  /** A 100.00 product — five of them make the story's 500.00 ticket. */
  private UUID createProduct(UUID org, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("100.00"))
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

  private BigDecimal paymentUnallocated(UUID paymentId) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
  }

  private BigDecimal paymentRefunded(UUID paymentId) {
    return dsl.select(PAYMENT.REFUNDED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.REFUNDED_AMOUNT);
  }

  private BigDecimal allocatedTotal(UUID invoiceId) {
    BigDecimal sum =
        dsl.select(org.jooq.impl.DSL.sum(PAYMENT_ALLOCATION.AMOUNT))
            .from(PAYMENT_ALLOCATION)
            .where(PAYMENT_ALLOCATION.SALES_INVOICE_ID.eq(invoiceId))
            .fetchOne(0, BigDecimal.class);
    return sum == null ? BigDecimal.ZERO : sum;
  }

  private int milestoneCount(UUID org, String milestone) {
    return dsl.fetchCount(
        dsl.selectFrom(ORG_MILESTONE)
            .where(ORG_MILESTONE.ORG_ID.eq(org).and(ORG_MILESTONE.MILESTONE.eq(milestone))));
  }

  private int stockQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private int soldLogCount(UUID org, UUID product) {
    return dsl.fetchCount(
        dsl.selectFrom(INVENTORY_LOG)
            .where(
                INVENTORY_LOG
                    .ORG_ID
                    .eq(org)
                    .and(INVENTORY_LOG.PRODUCT_ID.eq(product))
                    .and(INVENTORY_LOG.REASON.eq(StockReason.SOLD))));
  }

  private int tableCount(org.jooq.Table<?> table) {
    return dsl.fetchCount(table);
  }

  /** VERIFIED cash DEBIT transactions in the org — the recorded counter-change hand-backs. */
  private int cashDebitTxnCount(UUID org) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION)
            .where(
                PAYMENT_TRANSACTION
                    .ORG_ID
                    .eq(org)
                    .and(
                        PAYMENT_TRANSACTION.DIRECTION.eq(
                            com.loai.inventory.repository.generated.enums.PaymentDirection.DEBIT))
                    .and(
                        PAYMENT_TRANSACTION.PROVIDER.eq(
                            com.loai.inventory.repository.generated.enums.PaymentProvider.cash))));
  }
}
