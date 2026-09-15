package com.loai.inventory.api.ledger;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.JOURNAL_ENTRY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CashMovementKind;
import com.loai.inventory.domain.model.CreditNoteReason;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.model.ledger.AccountStatement;
import com.loai.inventory.domain.model.ledger.JournalEntryView;
import com.loai.inventory.domain.model.ledger.LedgerChart;
import com.loai.inventory.domain.model.ledger.LedgerHealth;
import com.loai.inventory.domain.model.ledger.PostingSummary;
import com.loai.inventory.domain.model.ledger.Side;
import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import com.loai.inventory.repository.CashMovementRepositoryFactoryImpl;
import com.loai.inventory.repository.CashShiftRepositoryFactoryImpl;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.LedgerRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.CashShiftService;
import com.loai.inventory.service.CounterReturnService;
import com.loai.inventory.service.CounterReturnService.LineInput;
import com.loai.inventory.service.CounterReturnService.ReturnCommand;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CreditNoteService.IssueCommand;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.LedgerService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.RefundService.CreateCommand;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The general ledger over the real money and stock services (stories/general_ledger.md §Tests):
 * every flow the shop runs — the counter sale with change, the online delivery, the credit note and
 * its refund, the void, the counter return, the restock / adjustment, the drawer's day — is driven
 * through the production services, then the poster derives the journal and the test asserts the
 * postings, the balance identity, the independent cross-checks in {@code /health}, idempotence, the
 * rebuild, isolation, the statement's running balance, and the database's refusal to commit an
 * unbalanced entry. The {@code CashShiftIT} harness with the ledger wired beside it.
 */
@Testcontainers
class GeneralLedgerIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService sales;
  static CounterReturnService counterReturns;
  static RefundService refunds;
  static CashShiftService shifts;
  static CreditNoteService creditNotes;
  static FulfillmentService fulfillments;
  static InventoryService inventory;
  static InvoiceAdminService invoiceAdmin;
  static LedgerService ledger;

  private final AtomicInteger seq = new AtomicInteger(1);

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

    shifts =
        new CashShiftService(
            dsl,
            new CashShiftRepositoryFactoryImpl(),
            new CashMovementRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl());
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
    refunds =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            shifts);
    fulfillments =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            invoiceService,
            refunds,
            reservationService,
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
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()),
            shifts);
    com.loai.inventory.service.MagicLinkService magicLink =
        com.loai.inventory.api.support.TestWiring.magicLinkService(dsl);
    sales =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillments,
            paymentService,
            invoiceService,
            refunds,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            magicLink,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.CouponService(
                dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    creditNotes =
        new CreditNoteService(
            dsl,
            new CreditNoteRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
    counterReturns =
        new CounterReturnService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            creditNotes,
            refunds);
    inventory =
        new InventoryService(
            dsl,
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new ProductRepositoryImpl(dsl),
            new SalesOrderRepositoryFactoryImpl());
    invoiceAdmin =
        new InvoiceAdminService(
            dsl,
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            invoiceService);
    ledger =
        new LedgerService(dsl, new LedgerRepositoryFactoryImpl(), new OrgRepositoryFactoryImpl());
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
        "TRUNCATE journal_line, journal_entry, ledger_account, cash_movement, cash_shift,"
            + " refund_allocation, refund, credit_note_line, credit_note,"
            + " credit_note_number_counter, payment_allocation, sales_invoice_line, sales_invoice,"
            + " payment, payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, sales_order_line, sales_order, customer, product,"
            + " user_org_role, app_user, org, order_number_counter, invoice_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  // The counter

  /**
   * One cash sale, tendered over: the invoice, the receipt, the allocation, the change and the COGS
   * are five balanced entries; cash nets to the price, the receivable and the deposit to zero, and
   * both independent cross-checks agree. Stock came in through {@code initialise}, so the shelf
   * value and the unbilled supplier show too.
   */
  @Test
  void cashSaleWithChange_postsFiveBalancedEntries_andTheBooksAgreeWithTheApp() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID pen = createProduct(org, "PEN", "10.00", "4.00");
    inventory.initialise(org, pen, 10, actor(owner)); // RESTOCK 10 × 4.00 = 40.00

    InStoreSale sale = sell(org, owner, pen, 3, PaymentProvider.CASH, "50.00"); // 30.00, change 20
    assertMoney("20.00", sale.changeAmount());

    PostingSummary posted = ledger.catchUp(org);
    assertEquals(2, posted.inserted().get("STOCK/MOVED"), "restock + sale = two stock moves");
    assertEquals(1, posted.inserted().get("INVOICE/ISSUED"));
    assertEquals(1, posted.inserted().get("RECEIPT/RECEIVED"));
    assertEquals(1, posted.inserted().get("ALLOCATION/APPLIED"));
    assertEquals(1, posted.inserted().get("REFUND/EXECUTED"), "the change is an executed refund");
    assertEquals(0, posted.inserted().get("CASH_MOVEMENT/RECORDED"));

    Map<String, TrialBalanceRow> tb = trialBalance(org);
    assertMoney("30.00", tb.get(LedgerChart.CASH).closing()); // 50 in, 20 back
    assertMoney("0.00", tb.get(LedgerChart.ACCOUNTS_RECEIVABLE).closing());
    assertMoney("0.00", tb.get(LedgerChart.CUSTOMER_DEPOSITS).closing());
    assertMoney("30.00", tb.get(LedgerChart.SALES_REVENUE).closing());
    assertMoney("12.00", tb.get(LedgerChart.COST_OF_GOODS_SOLD).closing()); // 3 × 4.00
    assertMoney("28.00", tb.get(LedgerChart.INVENTORY).closing()); // 40 − 12
    assertMoney("40.00", tb.get(LedgerChart.PURCHASES_UNBILLED).closing());
    assertBalanced(tb);

    LedgerHealth health = ledger.health(org);
    assertTrue(health.ok(), "health: " + health);
    assertEquals(0, health.uncostedStockMoves());
    assertEquals(6, health.postedEntries());
    for (LedgerHealth.Check c : health.checks()) {
      assertTrue(c.ok(), c.name() + " ledger=" + c.ledger() + " source=" + c.source());
    }
    // The V101 stamp: the restock row carries the product's cost at the moment it moved.
    assertMoney(
        "4.00",
        dsl.select(INVENTORY_LOG.UNIT_COST)
            .from(INVENTORY_LOG)
            .where(
                INVENTORY_LOG
                    .ORG_ID
                    .eq(org)
                    .and(
                        INVENTORY_LOG.REASON.eq(
                            com.loai.inventory.repository.generated.enums.StockReason.RESTOCK)))
            .fetchOne(INVENTORY_LOG.UNIT_COST));
  }

  /** A counter return with restock reverses the sale's entries leg by leg. */
  @Test
  void counterReturnWithRestock_reversesRevenueCashAndCogs() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID pen = createProduct(org, "PEN", "10.00", "4.00");
    inventory.initialise(org, pen, 10, actor(owner));
    InStoreSale sale = sell(org, owner, pen, 3, PaymentProvider.CASH, "30.00");

    counterReturns.returnFromReceipt(
        org,
        sale.order().getId(),
        new ReturnCommand(List.of(new LineInput(pen, 1)), true, "changed mind"),
        UUID.randomUUID().toString(),
        actor(owner),
        owner,
        true);

    Map<String, TrialBalanceRow> tb = trialBalance(org);
    assertMoney("20.00", tb.get(LedgerChart.CASH).closing());
    assertMoney("30.00", tb.get(LedgerChart.SALES_REVENUE).closing());
    assertMoney("10.00", tb.get(LedgerChart.SALES_RETURNS).closing());
    assertMoney("0.00", tb.get(LedgerChart.ACCOUNTS_RECEIVABLE).closing());
    assertMoney("8.00", tb.get(LedgerChart.COST_OF_GOODS_SOLD).closing()); // 12 − 4
    assertMoney("32.00", tb.get(LedgerChart.INVENTORY).closing()); // 40 − 12 + 4
    assertBalanced(tb);
    assertTrue(ledger.health(org).ok());
  }

  // Online

  /**
   * An online prepayment then delivery: the receipt lands in deposits, the delivery issues the
   * invoice and applies the deposit to the receivable. Then a credit note and its refund: the note
   * reduces the receivable, the refund clears it and cash — and the receivable cross-check (which
   * nets notes and refunds against invoices from the app's own cached fields) still agrees.
   */
  @Test
  void onlineDelivery_thenCreditNoteAndRefund_keepTheReceivableCheckTrue() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID customer = createCustomer(org);
    UUID book = createProduct(org, "BOOK", "10.00", "6.00");
    createInventory(org, book, 15, 10);
    UUID order = seedPaidOnlineOrder(org, customer, book, 10, "100.00", "100.00");

    Map<String, TrialBalanceRow> prepaid = trialBalance(org);
    assertMoney("100.00", prepaid.get(LedgerChart.BANK).closing());
    assertMoney("100.00", prepaid.get(LedgerChart.CUSTOMER_DEPOSITS).closing());
    assertMoney("0.00", prepaid.get(LedgerChart.ACCOUNTS_RECEIVABLE).closing());

    UUID invoice = deliver(org, order, owner).invoice().getId();
    Map<String, TrialBalanceRow> delivered = trialBalance(org);
    assertMoney("100.00", delivered.get(LedgerChart.SALES_REVENUE).closing());
    assertMoney("0.00", delivered.get(LedgerChart.CUSTOMER_DEPOSITS).closing());
    assertMoney("0.00", delivered.get(LedgerChart.ACCOUNTS_RECEIVABLE).closing());
    assertMoney("0.00", delivered.get(LedgerChart.INVENTORY).closing(), "no costed restock");
    // 10 × unit_cost — the seeded line carries no unit_cost, so the ship is an uncosted move:
    // recorded once in ledger_skip (never re-costed), counted as coverage, not as a fault.
    LedgerHealth afterShip = ledger.health(org);
    assertEquals(1, afterShip.uncostedStockMoves());
    assertEquals(Map.of("STOCK/MOVED:UNCOSTED", 1L), afterShip.skipped());
    assertEquals(0L, afterShip.unposted().get("STOCK/MOVED"), "a skipped row is not drift");
    assertEquals(1, dsl.fetchCount(com.loai.inventory.repository.generated.Tables.LEDGER_SKIP));

    UUID note =
        creditNotes
            .issue(
                org,
                new IssueCommand(
                    invoice,
                    CreditNoteReason.RETURN,
                    "one book back",
                    List.of(
                        new CreditNoteService.LineSpec(
                            book, "book", 1, new BigDecimal("10.00"), BigDecimal.ZERO))),
                false)
            .creditNote()
            .getId();
    Map<String, TrialBalanceRow> credited = trialBalance(org);
    assertMoney("10.00", credited.get(LedgerChart.SALES_RETURNS).closing());
    assertMoney("-10.00", credited.get(LedgerChart.ACCOUNTS_RECEIVABLE).closing(), "we owe 10");
    LedgerHealth afterNote = ledger.health(org);
    assertTrue(afterNote.checks().get(0).ok(), "AR check after the note: " + afterNote.checks());

    UUID refund =
        refunds
            .create(
                org,
                new CreateCommand(
                    note,
                    null,
                    new BigDecimal("10.00"),
                    "EGP",
                    PaymentProvider.INSTAPAY_MANUAL,
                    null),
                false)
            .getId();
    refunds.execute(org, refund, "IP-BACK-1", owner);
    Map<String, TrialBalanceRow> refunded = trialBalance(org);
    assertMoney("0.00", refunded.get(LedgerChart.ACCOUNTS_RECEIVABLE).closing());
    assertMoney("90.00", refunded.get(LedgerChart.BANK).closing());
    assertBalanced(refunded);
    LedgerHealth health = ledger.health(org);
    assertTrue(health.ok(), "health: " + health);
  }

  /** Void + reissue: the issue entry stays, the void reverses it, the replacement posts anew. */
  @Test
  void voidAndReissue_isAReversalPlusANewEntry_neverAnEdit() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID customer = createCustomer(org);
    UUID book = createProduct(org, "BOOK", "10.00", null);
    createInventory(org, book, 5, 2);
    UUID order = seedUnpaidOnlineOrder(org, customer, book, 2, "20.00");
    UUID invoice = deliver(org, order, owner).invoice().getId();

    assertMoney("20.00", trialBalance(org).get(LedgerChart.ACCOUNTS_RECEIVABLE).closing());

    invoiceAdmin.voidInvoice(org, invoice, "wrong customer");
    Map<String, TrialBalanceRow> voided = trialBalance(org);
    assertMoney("0.00", voided.get(LedgerChart.ACCOUNTS_RECEIVABLE).closing());
    assertMoney("0.00", voided.get(LedgerChart.SALES_REVENUE).closing());
    assertMoney("20.00", voided.get(LedgerChart.SALES_REVENUE).debit(), "the reversal is a debit");
    assertMoney("20.00", voided.get(LedgerChart.SALES_REVENUE).credit(), "the issue stays");

    List<JournalEntryView> journal =
        ledger.journal(org, null, soon(), null, null, null).result().items();
    assertEquals(
        List.of("INVOICE/ISSUED", "INVOICE/VOIDED"),
        journal.stream()
            .filter(e -> e.sourceType().equals("INVOICE"))
            .map(e -> e.sourceType() + "/" + e.event())
            .toList());
    assertTrue(ledger.health(org).ok());
  }

  // The drawer

  @Test
  void drawerMovementsAndAShortClose_postAgainstCash() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID pen = createProduct(org, "PEN", "10.00", null);
    createInventory(org, pen, 10, 0);
    sell(org, owner, pen, 1, PaymentProvider.CASH, "10.00"); // auto-opens the shift, float 0
    UUID shift = shifts.current(org).orElseThrow().shift().getId();
    shifts.addMovement(
        org, shift, CashMovementKind.PAY_IN, new BigDecimal("50.00"), "float", owner, true);
    shifts.addMovement(
        org, shift, CashMovementKind.PAY_OUT, new BigDecimal("15.00"), "bank drop", owner, true);
    // expected = 0 + 10 + 50 − 15 = 45; counted 42 → 3.00 short
    shifts.close(org, shift, new BigDecimal("42.00"), null, owner, true);

    Map<String, TrialBalanceRow> tb = trialBalance(org);
    assertMoney(
        "42.00", tb.get(LedgerChart.CASH).closing(), "the books hold what the drawer holds");
    assertMoney("50.00", tb.get(LedgerChart.OWNER_CONTRIBUTIONS).closing());
    assertMoney("15.00", tb.get(LedgerChart.OWNER_DRAWINGS).closing());
    assertMoney("3.00", tb.get(LedgerChart.CASH_OVER_SHORT).closing());
    assertBalanced(tb);
    assertTrue(ledger.health(org).ok());
  }

  /** A count that finds units missing writes shrinkage; one that finds extra writes a gain. */
  @Test
  void stocktakeVariance_isShrinkageOrGain_atTheStampedCost() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID pen = createProduct(org, "PEN", "10.00", "2.50");
    inventory.initialise(org, pen, 10, actor(owner));
    inventory.adjust(org, pen, -2, StockReason.STOCKTAKE, actor(owner), null);
    inventory.adjust(org, pen, 1, StockReason.ADJUSTMENT, actor(owner), null);

    Map<String, TrialBalanceRow> tb = trialBalance(org);
    assertMoney("22.50", tb.get(LedgerChart.INVENTORY).closing()); // 25 − 5 + 2.5
    assertMoney("2.50", tb.get(LedgerChart.INVENTORY_ADJUSTMENTS).closing()); // 5 − 2.5
    assertBalanced(tb);
  }

  // Reliability

  @Test
  void catchUpIsIdempotent_resetRebuildRenumbersFromOne_andReadsAreCurrent() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID pen = createProduct(org, "PEN", "10.00", "4.00");
    inventory.initialise(org, pen, 10, actor(owner));
    sell(org, owner, pen, 1, PaymentProvider.CASH, "10.00");

    int first = ledger.catchUp(org).total();
    assertEquals(5, first);
    assertEquals(0, ledger.catchUp(org).total(), "a second run posts nothing");
    assertEquals(
        0, ledger.rebuild(org, false).posted().total(), "rebuild without reset = catch-up");

    LedgerService.Rebuilt rebuilt = ledger.rebuild(org, true);
    assertEquals(5, rebuilt.removed());
    assertEquals(5, rebuilt.posted().total());
    assertEquals(0, dsl.fetchCount(com.loai.inventory.repository.generated.Tables.LEDGER_SKIP));
    List<Long> numbers =
        dsl.select(JOURNAL_ENTRY.ENTRY_NO)
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORG_ID.eq(org))
            .orderBy(JOURNAL_ENTRY.ENTRY_NO)
            .fetch(JOURNAL_ENTRY.ENTRY_NO);
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L), numbers);

    // A read after a new sale sees it without anyone calling the poster.
    sell(org, owner, pen, 2, PaymentProvider.INSTAPAY_IN_STORE, "20.00");
    assertMoney("30.00", trialBalance(org).get(LedgerChart.SALES_REVENUE).closing());
    assertMoney("20.00", trialBalance(org).get(LedgerChart.BANK).closing());
  }

  @Test
  void orgsAreIsolated() {
    UUID a = createOrg("a");
    UUID b = createOrg("b");
    UUID owner = createUser("owner@acme.test");
    UUID penA = createProduct(a, "PEN", "10.00", null);
    UUID penB = createProduct(b, "PEN", "10.00", null);
    createInventory(a, penA, 5, 0);
    createInventory(b, penB, 5, 0);
    sell(a, owner, penA, 1, PaymentProvider.CASH, "10.00");
    sell(b, owner, penB, 3, PaymentProvider.CASH, "30.00");

    assertMoney("10.00", trialBalance(a).get(LedgerChart.SALES_REVENUE).closing());
    assertMoney("30.00", trialBalance(b).get(LedgerChart.SALES_REVENUE).closing());
    assertEquals(3, ledger.health(a).postedEntries());
    assertEquals(3, ledger.health(b).postedEntries());
  }

  @Test
  void statement_runsTheBalanceAcrossPages_andJournalFiltersByAccount() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID pen = createProduct(org, "PEN", "10.00", null);
    createInventory(org, pen, 10, 0);
    sell(org, owner, pen, 1, PaymentProvider.CASH, "10.00");
    sell(org, owner, pen, 2, PaymentProvider.CASH, "20.00");
    sell(org, owner, pen, 3, PaymentProvider.CASH, "50.00"); // change 20

    AccountStatement page1 =
        ledger.statement(org, LedgerChart.CASH, null, soon(), "0", "2").result();
    AccountStatement page2 =
        ledger.statement(org, LedgerChart.CASH, null, soon(), "1", "2").result();
    assertEquals(4, page1.total(), "three receipts and one change hand-back touch cash");
    assertMoney("0.00", page1.opening());
    assertMoney("60.00", page1.closing()); // 10 + 20 + 50 − 20
    assertMoney("10.00", page1.items().get(0).balanceAfter());
    assertMoney("30.00", page1.items().get(1).balanceAfter());
    assertMoney("80.00", page2.items().get(0).balanceAfter(), "page 2 continues page 1");
    assertMoney("60.00", page2.items().get(1).balanceAfter());
    assertEquals(Side.CR, page2.items().get(1).side());

    List<JournalEntryView> onDeposits =
        ledger
            .journal(org, null, soon(), LedgerChart.CUSTOMER_DEPOSITS, null, null)
            .result()
            .items();
    assertEquals(7, onDeposits.size(), "3 receipts + 3 allocations + 1 change");
    assertTrue(
        onDeposits.stream()
            .allMatch(
                e ->
                    e.lines().stream()
                        .anyMatch(l -> l.accountCode().equals(LedgerChart.CUSTOMER_DEPOSITS))));
    List<JournalEntryView> all =
        ledger.journal(org, null, soon(), null, null, null).result().items();
    assertEquals(10, all.size(), "3 invoices + 3 receipts + 3 allocations + 1 change");
    for (int i = 1; i < all.size(); i++) {
      assertTrue(
          !all.get(i).postedAt().isBefore(all.get(i - 1).postedAt()),
          "journal is in posting order");
    }
  }

  /** The V101 trigger: a lone leg cannot be committed by anyone, poster or hand. */
  @Test
  void theDatabaseRefusesAnUnbalancedEntry() {
    UUID org = createOrg("acme");
    ledger.catchUp(org); // materialises the chart
    UUID cash =
        dsl.select(com.loai.inventory.repository.generated.Tables.LEDGER_ACCOUNT.ID)
            .from(com.loai.inventory.repository.generated.Tables.LEDGER_ACCOUNT)
            .where(
                com.loai.inventory.repository.generated.Tables.LEDGER_ACCOUNT
                    .ORG_ID
                    .eq(org)
                    .and(
                        com.loai.inventory.repository.generated.Tables.LEDGER_ACCOUNT.CODE.eq(
                            "1000")))
            .fetchOne(com.loai.inventory.repository.generated.Tables.LEDGER_ACCOUNT.ID);

    DataAccessException refused =
        assertThrows(
            DataAccessException.class,
            () ->
                dsl.transaction(
                    cfg -> {
                      DSLContext tx = DSL.using(cfg);
                      UUID entry = UUID.randomUUID();
                      tx.execute(
                          "INSERT INTO journal_entry (id, org_id, entry_no, posted_at, source_type,"
                              + " source_id, event, memo) VALUES ({0}, {1}, 999, now(), 'INVOICE',"
                              + " 'hand', 'ISSUED', 'by hand')",
                          DSL.val(entry), DSL.val(org));
                      tx.execute(
                          "INSERT INTO journal_line (entry_id, org_id, account_id, posted_at, seq,"
                              + " side, amount) VALUES ({0}, {1}, {2}, now(), 1, 'DR', 5.00)",
                          DSL.val(entry), DSL.val(org), DSL.val(cash));
                    }));
    // jOOQ reports the failed COMMIT ("Cannot commit transaction"); the trigger's text is the
    // cause.
    StringBuilder chain = new StringBuilder();
    for (Throwable t = refused; t != null; t = t.getCause()) {
      chain.append(t.getMessage()).append(" | ");
    }
    assertTrue(chain.toString().contains("unbalanced"), chain.toString());
    assertEquals(0, dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORG_ID.eq(org)));
  }

  // helpers

  private Map<String, TrialBalanceRow> trialBalance(UUID org) {
    Map<String, TrialBalanceRow> byCode = new java.util.LinkedHashMap<>();
    for (TrialBalanceRow r : ledger.trialBalance(org, null, soon()).rows()) {
      byCode.put(r.code(), r);
    }
    assertEquals(LedgerChart.ACCOUNTS.size(), byCode.size(), "every chart account is a row");
    return byCode;
  }

  private static void assertBalanced(Map<String, TrialBalanceRow> tb) {
    BigDecimal dr = BigDecimal.ZERO;
    BigDecimal cr = BigDecimal.ZERO;
    BigDecimal assets = BigDecimal.ZERO;
    BigDecimal claims = BigDecimal.ZERO;
    for (TrialBalanceRow r : tb.values()) {
      dr = dr.add(r.debit());
      cr = cr.add(r.credit());
      // Assets = liabilities + equity + (revenue − expenses): every non-asset closing is read
      // toward the credit side, so a DR-normal contra (drawings, discounts, returns) subtracts.
      if (r.type() == com.loai.inventory.domain.model.ledger.AccountType.ASSET) {
        assets = assets.add(r.closing());
      } else {
        claims = claims.add(r.normalSide() == Side.CR ? r.closing() : r.closing().negate());
      }
    }
    assertMoney(dr.toPlainString(), cr);
    assertMoney(assets.toPlainString(), claims);
  }

  private InStoreSale sell(
      UUID org, UUID actor, UUID product, int qty, PaymentProvider provider, String amount) {
    return sales.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, qty)),
        List.of(
            new PaymentInput(
                provider,
                provider == PaymentProvider.CASH ? null : "IPN-" + UUID.randomUUID(),
                new BigDecimal(amount))),
        null,
        null,
        actor(actor),
        UUID.randomUUID().toString(),
        actor,
        true);
  }

  private DeliveredView deliver(UUID org, UUID orderId, UUID actorUser) {
    UUID lineId =
        dsl.select(SALES_ORDER_LINE.ID)
            .from(SALES_ORDER_LINE)
            .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(orderId))
            .fetchAny(SALES_ORDER_LINE.ID);
    UUID f =
        fulfillments
            .create(
                org,
                orderId,
                List.of(new FulfillmentService.LineInput(lineId)),
                null,
                null,
                null,
                actor(actorUser))
            .fulfillment()
            .getId();
    fulfillments.ship(org, f, actor(actorUser));
    return fulfillments.markDelivered(org, f, actor(actorUser));
  }

  private UUID seedPaidOnlineOrder(
      UUID org, UUID customer, UUID product, int qty, String grandTotal, String paid) {
    UUID orderId = seedOnlineOrder(org, customer, product, qty, grandTotal, paid, OrderStatus.PAID);
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(paid))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusHours(2))
        .execute();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, UUID.randomUUID())
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.CUSTOMER_ID, customer)
        .set(PAYMENT.SALES_ORDER_ID, orderId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(paid))
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(paid))
        .set(PAYMENT.STATUS, com.loai.inventory.repository.generated.enums.PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, now().minusHours(2))
        .execute();
    return orderId;
  }

  private UUID seedUnpaidOnlineOrder(
      UUID org, UUID customer, UUID product, int qty, String grandTotal) {
    // PAID with no prepayment: delivery still issues the invoice, which stays ISSUED and unpaid —
    // the inert state void+reissue exists for.
    return seedOnlineOrder(org, customer, product, qty, grandTotal, "0.00", OrderStatus.PAID);
  }

  private UUID seedOnlineOrder(
      UUID org,
      UUID customer,
      UUID product,
      int qty,
      String grandTotal,
      String prepaid,
      OrderStatus status) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    BigDecimal grand = new BigDecimal(grandTotal);
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.SUBTOTAL, grand)
        .set(SALES_ORDER.GRAND_TOTAL, grand)
        .set(SALES_ORDER.PREPAID_AMOUNT, new BigDecimal(prepaid))
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
    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, UUID.randomUUID())
        .set(INVENTORY_RESERVATION.ORG_ID, org)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, product)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, qty)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .execute();
    return orderId;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /**
   * An explicit window end an hour ahead. Stock moves are dated by the database clock ({@code
   * inventory_log.created_at DEFAULT now()}) while every money row carries a JVM instant, and the
   * Postgres container's clock runs a few tens of milliseconds ahead of the JVM here — so a default
   * {@code to = now()} taken right after a move can exclude it. Production shares one clock.
   */
  private static String soon() {
    return now().plusHours(1).toString();
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertMoney(expected, actual, null);
  }

  private static void assertMoney(String expected, BigDecimal actual, String why) {
    assertNotNull(actual, "expected " + expected + " got null" + (why == null ? "" : " — " + why));
    assertEquals(
        0,
        new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " got " + actual + (why == null ? "" : " — " + why));
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

  private UUID createCustomer(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, id + "-nadia@acme.test")
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String sku, String basePrice, String costPrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal(basePrice))
        .set(PRODUCT.COST_PRICE, costPrice == null ? null : new BigDecimal(costPrice))
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
}
