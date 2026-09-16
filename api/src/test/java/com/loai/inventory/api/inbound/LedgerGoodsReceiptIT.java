package com.loai.inventory.api.inbound;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.JOURNAL_ENTRY;
import static com.loai.inventory.repository.generated.Tables.JOURNAL_LINE;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.domain.model.ledger.AccountType;
import com.loai.inventory.domain.model.ledger.LedgerChart;
import com.loai.inventory.domain.model.ledger.LedgerHealth;
import com.loai.inventory.domain.model.ledger.Side;
import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import com.loai.inventory.repository.CashMovementRepositoryFactoryImpl;
import com.loai.inventory.repository.CashShiftRepositoryFactoryImpl;
import com.loai.inventory.repository.CouponRepositoryFactoryImpl;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.GoodsReceiptRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.LedgerRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.SupplierRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.CashShiftService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.GoodsReceiptService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.LedgerService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.SupplierService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The receipt through the general ledger ({@code stories/supplier_goods_receipt.md} §Tests), on the
 * {@code GeneralLedgerIT} harness.
 *
 * <p>Three claims: a receipt adds <b>no template of its own</b> — its lines post through V101's
 * {@code STOCK/MOVED} at the cost on the note; a void reverses to zero, which only works because
 * the {@code RESTOCK} arm became sign-aware in this slice; and that change is invisible to every
 * entry already posted, asserted by a {@code reset} rebuild over a pre-existing fixture rather than
 * assumed.
 */
@Testcontainers
class LedgerGoodsReceiptIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static LedgerService ledger;
  static InventoryService inventory;
  static SalesOrderService sales;
  static SupplierService suppliers;
  static GoodsReceiptService receipts;

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

    CashShiftService shifts =
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
    RefundService refunds =
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
    FulfillmentService fulfillments =
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
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.CouponService(dsl, new CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    inventory =
        new InventoryService(
            dsl,
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new ProductRepositoryImpl(dsl),
            new SalesOrderRepositoryFactoryImpl(),
            new GoodsReceiptRepositoryFactoryImpl());
    suppliers =
        new SupplierService(
            dsl, new SupplierRepositoryFactoryImpl(), new GoodsReceiptRepositoryFactoryImpl());
    receipts =
        new GoodsReceiptService(
            dsl,
            new GoodsReceiptRepositoryFactoryImpl(),
            new SupplierRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new ProductRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl());
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
        "TRUNCATE journal_line, journal_entry, ledger_account, ledger_skip, cash_movement,"
            + " cash_shift, refund_allocation, refund, credit_note_line, credit_note,"
            + " credit_note_number_counter, payment_allocation, sales_invoice_line, sales_invoice,"
            + " payment, payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " goods_receipt_line, goods_receipt, goods_receipt_number_counter, supplier,"
            + " inventory_log, inventory, sales_order_line, sales_order, customer, product,"
            + " user_org_role, app_user, org, order_number_counter, invoice_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  @Test
  void receipt_postsThroughTheStockTemplateOnly_movingInventoryAndPurchasesByTheTotal() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0);
    UUID pad = createTrackedProduct(org, "PAD", null, 0);

    receipts.record(
        org,
        new GoodsReceiptService.ReceiptCommand(
            zaki.getId(),
            null,
            "DN-88213",
            null,
            List.of(line(pen, 24, "12.50"), line(pad, 3, "40.00"))),
        "k-1",
        actor(owner),
        owner);

    var posted = ledger.catchUp(org);
    assertEquals(2, posted.inserted().get("STOCK/MOVED"), "one entry per line, nothing else");
    assertEquals(2, posted.total(), "a receipt adds NO template of its own");

    Map<String, TrialBalanceRow> tb = trialBalance(org);
    assertMoney("420.00", tb.get(LedgerChart.INVENTORY).closing());
    assertMoney("420.00", tb.get(LedgerChart.PURCHASES_UNBILLED).closing());
    assertBalanced(tb);

    LedgerHealth health = ledger.health(org);
    assertTrue(health.ok(), "health: " + health);
    assertEquals(0, health.uncostedStockMoves(), "a received product is costed by its note");
  }

  @Test
  void void_reversesBothAccounts_toANetOfZero() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 24, "12.50")), "k-1", actor(owner), owner)
            .receipt()
            .getId();
    ledger.catchUp(org);

    receipts.voidReceipt(org, id, "keyed 24 instead of 4", actor(owner), owner);
    var posted = ledger.catchUp(org);

    assertEquals(1, posted.inserted().get("STOCK/MOVED"), "the reversal is one more stock move");
    Map<String, TrialBalanceRow> tb = trialBalance(org);
    assertMoney("0.00", tb.get(LedgerChart.INVENTORY).closing());
    assertMoney("0.00", tb.get(LedgerChart.PURCHASES_UNBILLED).closing());
    assertMoney("300.00", tb.get(LedgerChart.INVENTORY).debit());
    assertMoney("300.00", tb.get(LedgerChart.INVENTORY).credit());
    assertMoney(
        "0.00",
        tb.get(LedgerChart.INVENTORY_ADJUSTMENTS).closing(),
        "a keying error is never shrinkage — that is why the void exists");
    assertBalanced(tb);
    assertTrue(ledger.health(org).ok());
  }

  @Test
  void signAwareRestockArm_changesNothingAlreadyPosted_rebuildIsByteIdentical() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    UUID pen = createProduct(org, "PEN", "4.00");
    // The pre-V102 fixture: a hand-keyed restock, a sale, an adjustment — every arm the STOCK
    // template had before the receipt existed.
    inventory.initialise(org, pen, 10, actor(owner));
    sell(org, owner, pen, 2);
    inventory.adjust(org, pen, -1, actor(owner));
    ledger.catchUp(org);

    List<String> before = entryFingerprints(org);
    int entries = dsl.fetchCount(dsl.selectFrom(JOURNAL_ENTRY).where(JOURNAL_ENTRY.ORG_ID.eq(org)));
    assertTrue(entries >= 4, "fixture posted " + entries + " entries");

    LedgerService.Rebuilt rebuilt = ledger.rebuild(org, true);

    assertEquals(entries, rebuilt.removed());
    assertEquals(entries, rebuilt.posted().total());
    assertEquals(before, entryFingerprints(org), "the sign-aware arm re-derives identical entries");
  }

  @Test
  void costFreezing_aSaleAfterTheReceiptTakesTheNewCost_oneBeforeKeepsTheOld() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createProduct(org, "PEN", "4.00");
    inventory.initialise(org, pen, 50, actor(owner));

    UUID before = sell(org, owner, pen, 1);
    receipts.record(
        org, delivery(zaki.getId(), line(pen, 10, "12.50")), "k-1", actor(owner), owner);
    UUID after = sell(org, owner, pen, 1);

    assertMoney("4.00", frozenCost(before), "a cost typed today never re-prices yesterday's sale");
    assertMoney("12.50", frozenCost(after), "the next sale's COGS is the cost we actually paid");
  }

  @Test
  void freeOfChargeLine_movesStock_postsNoJournalLine_andIsCountedAsUncosted() {
    UUID org = createOrg("acme");
    UUID owner = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0);

    receipts.record(org, delivery(zaki.getId(), line(pen, 6, "0.00")), "k-1", actor(owner), owner);
    ledger.catchUp(org);

    assertEquals(
        0,
        dsl.fetchCount(dsl.selectFrom(JOURNAL_LINE).where(JOURNAL_LINE.ORG_ID.eq(org))),
        "journal_line.amount > 0 — a free delivery posts nothing, honestly");
    assertEquals(1, ledger.health(org).uncostedStockMoves());
    assertEquals(6, stockOf(pen), "the stock moved all the same");
  }

  // fixtures

  private List<String> entryFingerprints(UUID org) {
    return dsl.select(
            JOURNAL_ENTRY.SOURCE_TYPE,
            JOURNAL_ENTRY.EVENT,
            JOURNAL_ENTRY.SOURCE_ID,
            JOURNAL_ENTRY.POSTED_AT,
            JOURNAL_ENTRY.MEMO,
            JOURNAL_LINE.SEQ,
            JOURNAL_LINE.SIDE,
            JOURNAL_LINE.AMOUNT,
            JOURNAL_LINE.ACCOUNT_ID)
        .from(JOURNAL_ENTRY)
        .join(JOURNAL_LINE)
        .on(JOURNAL_LINE.ENTRY_ID.eq(JOURNAL_ENTRY.ID))
        .where(JOURNAL_ENTRY.ORG_ID.eq(org))
        .orderBy(
            JOURNAL_ENTRY.SOURCE_TYPE,
            JOURNAL_ENTRY.EVENT,
            JOURNAL_ENTRY.SOURCE_ID,
            JOURNAL_LINE.SEQ)
        .fetch()
        .map(Object::toString);
  }

  private Map<String, TrialBalanceRow> trialBalance(UUID org) {
    Map<String, TrialBalanceRow> byCode = new LinkedHashMap<>();
    for (TrialBalanceRow r :
        ledger
            .trialBalance(org, null, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString())
            .rows()) {
      byCode.put(r.code(), r);
    }
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
      if (r.type() == AccountType.ASSET) {
        assets = assets.add(r.closing());
      } else {
        claims = claims.add(r.normalSide() == Side.CR ? r.closing() : r.closing().negate());
      }
    }
    assertMoney(dr.toPlainString(), cr);
    assertMoney(assets.toPlainString(), claims);
  }

  private UUID sell(UUID org, UUID owner, UUID product, int qty) {
    return sales
        .placeInStoreSale(
            org,
            null,
            List.of(new OrderLineInput(product, qty)),
            List.of(
                new PaymentInput(
                    PaymentProvider.CASH,
                    null,
                    new BigDecimal("10.00").multiply(BigDecimal.valueOf(qty)))),
            null,
            null,
            actor(owner),
            UUID.randomUUID().toString(),
            owner,
            true)
        .order()
        .getId();
  }

  private BigDecimal frozenCost(UUID orderId) {
    return dsl.select(SALES_ORDER_LINE.UNIT_COST)
        .from(SALES_ORDER_LINE)
        .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(orderId))
        .fetchAny(SALES_ORDER_LINE.UNIT_COST);
  }

  private static ActorContext actor(UUID userId) {
    return ActorContext.user(userId.toString());
  }

  private static SupplierService.SupplierEdit named(String name) {
    return new SupplierService.SupplierEdit(name, null, null, null, null, null);
  }

  private static GoodsReceiptService.LineCommand line(UUID product, int qty, String unitCost) {
    return new GoodsReceiptService.LineCommand(product, qty, new BigDecimal(unitCost));
  }

  private static GoodsReceiptService.ReceiptCommand delivery(
      UUID supplier, GoodsReceiptService.LineCommand... lines) {
    return new GoodsReceiptService.ReceiptCommand(supplier, null, null, null, List.of(lines));
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertMoney(expected, actual, null);
  }

  private static void assertMoney(String expected, BigDecimal actual, String why) {
    String tail = why == null ? "" : " — " + why;
    assertNotNull(actual, "expected " + expected + " got null" + tail);
    assertEquals(
        0,
        new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " got " + actual + tail);
  }

  private int stockOf(UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.PRODUCT_ID.eq(product))
        .fetchOne(INVENTORY.STOCK_QTY);
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

  private UUID createProduct(UUID org, String sku, String costPrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .set(PRODUCT.COST_PRICE, costPrice == null ? null : new BigDecimal(costPrice))
        .execute();
    return id;
  }

  private UUID createTrackedProduct(UUID org, String sku, String costPrice, int stockQty) {
    UUID id = createProduct(org, sku, costPrice);
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, id)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
    return id;
  }
}
