package com.loai.inventory.api.sale;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CASH_SHIFT;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ShiftRequiredException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CashMovementKind;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.CashMovementRepositoryFactoryImpl;
import com.loai.inventory.repository.CashShiftRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.CashShiftService;
import com.loai.inventory.service.CashShiftService.ShiftView;
import com.loai.inventory.service.CounterReturnService;
import com.loai.inventory.service.CounterReturnService.LineInput;
import com.loai.inventory.service.CounterReturnService.ReturnCommand;
import com.loai.inventory.service.CounterReturnService.Returned;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
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
 * The cash shift over real money ({@code stories/cash_shift.md}): the stamps the sale, the change
 * and the cash return leave on their transactions, the auto-open and the carried float, the
 * expected-cash arithmetic against those stamps, the gate, the one-open rule, close-once, and who
 * may close whose shift. The {@code CounterReturnIT} harness with the production stamper wired.
 */
@Testcontainers
class CashShiftIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService service;
  static CounterReturnService counterReturnService;
  static RefundService refundService;
  static CashShiftService shifts;

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
    refundService =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            shifts);
    FulfillmentService fulfillmentService =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            invoiceService,
            refundService,
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
    service =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            magicLink,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.CouponService(
                dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    CreditNoteService creditNoteService =
        new CreditNoteService(
            dsl,
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
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
        "TRUNCATE cash_movement, cash_shift, refund_allocation, refund, credit_note_line,"
            + " credit_note, credit_note_number_counter, payment_allocation, sales_invoice_line,"
            + " sales_invoice, payment, payment_transaction, fulfillment_line, fulfillment,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, user_org_role, app_user, org, order_number_counter,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // scenarios

  /** The sole owner's ordinary day: sell, and the shift is simply there — float 0, auto-opened. */
  @Test
  void firstCashSale_autoOpensTheShift_andStampsTheTender() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID product = createProduct(org, "pen", new BigDecimal("10.00"));
    createInventory(org, product, 20, 0);
    assertTrue(shifts.current(org).isEmpty());

    InStoreSale sale = sell(org, owner, product, 2, PaymentProvider.CASH, new BigDecimal("20.00"));

    ShiftView view = shifts.current(org).orElseThrow();
    assertTrue(view.shift().isAutoOpened());
    assertEquals(owner, view.shift().getOpenedBy());
    assertMoney("0.00", view.shift().getStartingCash());
    assertEquals(view.shift().getId(), stampOf(sale.payment().getPaymentTransactionId()));
    assertMoney("20.00", view.totals().cashSales());
    assertEquals(1, view.totals().receipts());
    assertMoney("20.00", view.expectedCash());

    // A second sale stamps the same shift — no second row.
    InStoreSale again = sell(org, owner, product, 1, PaymentProvider.CASH, new BigDecimal("10.00"));
    assertEquals(view.shift().getId(), stampOf(again.payment().getPaymentTransactionId()));
    assertEquals(1, dsl.fetchCount(CASH_SHIFT, CASH_SHIFT.ORG_ID.eq(org)));
  }

  /** The story's worked example, over the real ledger: 200 + 210 − 10 − 30 + 100 − 80 = 390. */
  @Test
  void expectedCash_isDerivedFromTheStampedLedgerAndTheMovements() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID product = createProduct(org, "pen", new BigDecimal("10.00"));
    createInventory(org, product, 50, 0);
    ShiftView opened = shifts.open(org, new BigDecimal("200"), "morning", owner);
    assertFalse(opened.shift().isAutoOpened());

    sell(org, owner, product, 15, PaymentProvider.CASH, new BigDecimal("150.00")); // exact
    InStoreSale over =
        sell(
            org,
            owner,
            product,
            5,
            PaymentProvider.CASH,
            new BigDecimal("60.00")); // 50 + change 10
    assertEquals(1, over.changeRefunds().size());
    InStoreSale instapay =
        sell(org, owner, product, 1, PaymentProvider.INSTAPAY_IN_STORE, new BigDecimal("10.00"));
    // 3 pens back for cash = 30.00 returned from the drawer.
    Returned ret =
        counterReturnService.returnFromReceipt(
            org,
            sell(org, owner, product, 3, PaymentProvider.CASH, new BigDecimal("30.00"))
                .order()
                .getId(),
            new ReturnCommand(List.of(new LineInput(product, 3)), true, null),
            UUID.randomUUID().toString(),
            actor(owner),
            owner,
            true);
    assertEquals("EXECUTED", ret.refund().getStatus().name());
    shifts.addMovement(
        org,
        opened.shift().getId(),
        CashMovementKind.PAY_IN,
        new BigDecimal("100"),
        "Change from the bank",
        owner,
        true);
    shifts.addMovement(
        org,
        opened.shift().getId(),
        CashMovementKind.PAY_OUT,
        new BigDecimal("80"),
        "Supplier",
        owner,
        true);

    ShiftView live = shifts.current(org).orElseThrow();
    assertMoney("240.00", live.totals().cashSales()); // 150 + 60 + 30 tendered in cash
    assertMoney("10.00", live.totals().changeGiven());
    assertMoney("30.00", live.totals().cashRefunds());
    assertMoney("10.00", live.totals().instapayTotal());
    assertMoney("100.00", live.totals().payIn());
    assertMoney("80.00", live.totals().payOut());
    assertEquals(4, live.totals().receipts());
    assertMoney("420.00", live.expectedCash()); // 200 + 240 − 10 − 30 + 100 − 80
    assertEquals(live.shift().getId(), stampOf(instapay.payment().getPaymentTransactionId()));
    assertEquals(live.shift().getId(), stampOf(ret.debit().getId()));

    ShiftView closed =
        shifts.close(org, opened.shift().getId(), new BigDecimal("415"), null, owner, true);
    assertMoney("420.00", closed.shift().getExpectedCash());
    assertMoney("-5.00", closed.difference());
    assertEquals(owner, closed.shift().getClosedBy());
    assertTrue(shifts.current(org).isEmpty());

    // The float carries: the next counter sale auto-opens with yesterday's count.
    sell(org, owner, product, 1, PaymentProvider.CASH, new BigDecimal("10.00"));
    ShiftView next = shifts.current(org).orElseThrow();
    assertTrue(next.shift().isAutoOpened());
    assertMoney("415.00", next.shift().getStartingCash());
    assertMoney("425.00", next.expectedCash());
  }

  @Test
  void gate_refusesTheSaleWhenRequiredAndNoneIsOpen_nothingWritten() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID product = createProduct(org, "pen", new BigDecimal("10.00"));
    createInventory(org, product, 5, 0);
    dsl.update(ORG).set(ORG.SHIFT_REQUIRED, true).where(ORG.ID.eq(org)).execute();

    assertThrows(
        ShiftRequiredException.class,
        () -> sell(org, owner, product, 1, PaymentProvider.CASH, new BigDecimal("10.00")));
    assertEquals(0, dsl.fetchCount(PAYMENT_TRANSACTION, PAYMENT_TRANSACTION.ORG_ID.eq(org)));
    assertEquals(5, stockQty(org, product));
    assertTrue(shifts.current(org).isEmpty());

    shifts.open(org, new BigDecimal("50"), null, owner);
    InStoreSale sale = sell(org, owner, product, 1, PaymentProvider.CASH, new BigDecimal("10.00"));
    assertNotNull(stampOf(sale.payment().getPaymentTransactionId()));
    assertEquals(4, stockQty(org, product));
  }

  @Test
  void oneOpenPerOrg_closeOnce_noMovementAfterClose() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    ShiftView s = shifts.open(org, new BigDecimal("10"), null, owner);
    assertThrows(
        ConflictException.class, () -> shifts.open(org, new BigDecimal("10"), null, owner));

    shifts.close(org, s.shift().getId(), new BigDecimal("10"), null, owner, true);
    assertThrows(
        ConflictException.class,
        () -> shifts.close(org, s.shift().getId(), new BigDecimal("10"), null, owner, true));
    assertThrows(
        ConflictException.class,
        () ->
            shifts.addMovement(
                org,
                s.shift().getId(),
                CashMovementKind.PAY_IN,
                BigDecimal.ONE,
                "late",
                owner,
                true));
    assertThrows(
        ConflictException.class,
        () -> shifts.setStartingCash(org, s.shift().getId(), BigDecimal.ONE, owner, true));
  }

  /**
   * STAFF may close only their own; MANAGER anyone's; the owner closing their own needs nothing.
   */
  @Test
  void ownership_staffCannotCloseAnothersShift_managerCan() {
    UUID org = createOrg("acme");
    UUID nadia = createUser("nadia@acme.test");
    UUID omar = createUser("omar@acme.test");
    ShiftView s = shifts.open(org, new BigDecimal("100"), null, nadia);

    assertThrows(
        AuthorizationException.class,
        () -> shifts.close(org, s.shift().getId(), new BigDecimal("100"), null, omar, false));
    assertThrows(
        AuthorizationException.class,
        () ->
            shifts.addMovement(
                org,
                s.shift().getId(),
                CashMovementKind.PAY_OUT,
                BigDecimal.ONE,
                "x",
                omar,
                false));
    // Her own, as STAFF: fine. Fix the float first.
    ShiftView fixed =
        shifts.setStartingCash(org, s.shift().getId(), new BigDecimal("120"), nadia, false);
    assertMoney("120.00", fixed.shift().getStartingCash());
    ShiftView closed =
        shifts.close(org, s.shift().getId(), new BigDecimal("120"), null, nadia, false);
    assertMoney("0.00", closed.difference());

    ShiftView s2 = shifts.open(org, new BigDecimal("5"), null, nadia);
    ShiftView byManager =
        shifts.close(org, s2.shift().getId(), new BigDecimal("7"), "manager count", omar, true);
    assertEquals(omar, byManager.shift().getClosedBy());
    assertMoney("2.00", byManager.difference());
  }

  /** A transfer refund executed from the queue never touched the drawer — no stamp. */
  @Test
  void transferRefund_isNotStamped_cashRefundIs() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID product = createProduct(org, "pen", new BigDecimal("10.00"));
    createInventory(org, product, 10, 0);
    InStoreSale viaInstapay =
        sell(org, owner, product, 2, PaymentProvider.INSTAPAY_IN_STORE, new BigDecimal("20.00"));
    Returned pending =
        counterReturnService.returnFromReceipt(
            org,
            viaInstapay.order().getId(),
            new ReturnCommand(List.of(new LineInput(product, 1)), true, null),
            UUID.randomUUID().toString(),
            actor(owner),
            owner,
            true);
    assertEquals("PENDING", pending.refund().getStatus().name());
    RefundService.Executed executed =
        refundService.execute(org, pending.refund().getId(), "BANK-RET-1", owner);
    assertNull(stampOf(executed.debit().getId()), "a transfer refund carries no shift");

    UUID shiftId = shifts.current(org).orElseThrow().shift().getId();
    Returned cash =
        counterReturnService.returnFromReceipt(
            org,
            sell(org, owner, product, 1, PaymentProvider.CASH, new BigDecimal("10.00"))
                .order()
                .getId(),
            new ReturnCommand(List.of(new LineInput(product, 1)), true, null),
            UUID.randomUUID().toString(),
            actor(owner),
            owner,
            true);
    assertEquals(shiftId, stampOf(cash.debit().getId()));
    ShiftView view = shifts.current(org).orElseThrow();
    assertMoney("10.00", view.totals().cashRefunds());
    assertMoney("20.00", view.totals().instapayTotal());
  }

  @Test
  void listAndDetail_newestFirst_differenceOnlyWhenClosed_movementsInOrder() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    ShiftView first = shifts.open(org, new BigDecimal("10"), null, owner);
    shifts.addMovement(
        org, first.shift().getId(), CashMovementKind.PAY_IN, new BigDecimal("5"), "a", owner, true);
    shifts.addMovement(
        org,
        first.shift().getId(),
        CashMovementKind.PAY_OUT,
        new BigDecimal("3"),
        "b",
        owner,
        true);
    shifts.close(org, first.shift().getId(), new BigDecimal("12"), null, owner, true);
    ShiftView second = shifts.open(org, new BigDecimal("12"), null, owner);

    CashShiftService.ShiftPage page = shifts.list(org, 0, 10);
    assertEquals(2, page.total());
    assertEquals(second.shift().getId(), page.items().get(0).shift().getId());
    assertNull(page.items().get(0).difference());
    assertMoney("0.00", page.items().get(1).difference());
    assertNotEquals(page.items().get(0).shift().getId(), page.items().get(1).shift().getId());

    CashShiftService.Detail detail = shifts.get(org, first.shift().getId());
    assertEquals(2, detail.movements().size());
    assertEquals(CashMovementKind.PAY_IN, detail.movements().get(0).movement().getKind());
    assertEquals("b", detail.movements().get(1).movement().getReason());
    assertEquals("owner@acme.test", detail.view().openedBy().name().replaceAll("^.*-", ""));
  }

  // helpers

  private InStoreSale sell(
      UUID org, UUID actor, UUID product, int qty, PaymentProvider provider, BigDecimal amount) {
    return service.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, qty)),
        List.of(
            new PaymentInput(
                provider,
                provider == PaymentProvider.CASH ? null : "IPN-" + UUID.randomUUID(),
                amount)),
        null,
        null,
        actor(actor),
        UUID.randomUUID().toString(),
        actor,
        true);
  }

  private UUID stampOf(UUID transactionId) {
    return dsl.select(PAYMENT_TRANSACTION.CASH_SHIFT_ID)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(transactionId))
        .fetchOne(PAYMENT_TRANSACTION.CASH_SHIFT_ID);
  }

  private int stockQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertNotNull(actual, "expected " + expected + " got null");
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
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
}
