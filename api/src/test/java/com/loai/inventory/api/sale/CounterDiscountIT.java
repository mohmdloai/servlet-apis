package com.loai.inventory.api.sale;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ApprovalRequiredException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Coupon;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SalesOrder;
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
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.DiscountInput;
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
 * Integration coverage for the counter discount on an in-store sale ({@code
 * stories/counter_discount.md}, V88). Same harness as {@link InStoreSaleIT} — one PostgreSQL
 * container, every Flyway migration, {@link SalesOrderService#placeInStoreSale} against the real
 * jOOQ repository factories — in its own class so the money-math cases read as one suite.
 *
 * <p>Headline properties: a MANAGER's discount flows through the order, the one invoice (which
 * takes the whole discount via {@code discountToBill}), the payment and the change — while a STAFF
 * request with the same block is refused as {@code ApprovalRequiredException} naming MANAGER, with
 * nothing written and no order number consumed.
 */
@Testcontainers
class CounterDiscountIT {

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

  private static final String YEAR = String.valueOf(java.time.Year.now().getValue());

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
  }

  private static DiscountInput percent(String value) {
    return new DiscountInput(CouponType.PERCENT, new BigDecimal(value), null);
  }

  private static DiscountInput fixed(String value) {
    return new DiscountInput(CouponType.FIXED, new BigDecimal(value), null);
  }

  private InStoreSale sell(
      UUID org,
      UUID product,
      int qty,
      BigDecimal tender,
      DiscountInput discount,
      UUID caller,
      boolean managerOrAdmin) {
    return service.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, qty)),
        new PaymentInput(PaymentProvider.CASH, null, tender),
        discount,
        null,
        actor(caller),
        UUID.randomUUID().toString(),
        caller,
        managerOrAdmin);
  }

  /**
   * MANAGER, PERCENT 10 on 3 × 100: the discount is the order's, the one invoice takes all of it
   * (the proration hands the goods-completing invoice the exact remainder), the payment is for the
   * discounted grand, stock moves once, and the row records what was keyed and who signed it.
   */
  @Test
  void manager_percentTen_flowsThroughOrderInvoicePaymentAndAudit() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);

    InStoreSale sale =
        sell(
            org,
            product,
            3,
            null,
            new DiscountInput(CouponType.PERCENT, new BigDecimal("10"), "  damaged   box "),
            manager,
            true);

    SalesOrder order = sale.order();
    assertEquals("CLOSED", order.getStatus().name());
    assertMoney("300.00", order.getSubtotal());
    assertMoney("30.00", order.getDiscountTotal());
    assertMoney("270.00", order.getGrandTotal());
    assertMoney("270.00", order.getPrepaidAmount());

    // The invoice: subtotal 300, discount 30, grand 270, paid 270 — one invoice, whole discount.
    assertEquals("PAID", sale.invoice().getStatus().name());
    assertMoney("300.00", sale.invoice().getSubtotal());
    assertMoney("30.00", sale.invoice().getDiscountTotal());
    assertMoney("270.00", sale.invoice().getGrandTotal());
    assertMoney("270.00", sale.invoice().getPaidAmount());
    assertMoney("30.00", invoiceDiscount(sale.invoice().getId()));

    // The payment: for the discounted grand, fully allocated, no change.
    assertMoney("270.00", sale.payment().getAmount());
    assertEquals("ALLOCATED", sale.payment().getStatus().name());
    assertMoney("0.00", paymentUnallocated(sale.payment().getId()));
    assertEquals(1, sale.allocations().size());
    assertNull(sale.changeRefund());

    // Goods left the shelf exactly once.
    assertEquals(7, stockQty(org, product));
    assertEquals(1, soldLogCount(org, product));

    // The audit is the order row (V88): type, value, normalised reason, grantor — and the echo.
    assertEquals("PERCENT", counterDiscountType(order.getId()));
    assertMoney("10.00", counterDiscountValue(order.getId()));
    assertEquals("damaged box", counterDiscountReason(order.getId()));
    assertEquals(manager, counterDiscountBy(order.getId()));
    assertEquals(CouponType.PERCENT, order.getCounterDiscountType());
    assertMoney("10.00", order.getCounterDiscountValue());
    assertEquals("damaged box", order.getCounterDiscountReason());
    assertEquals(manager, order.getCounterDiscountBy());
    assertNull(order.getCouponId(), "a counter discount is not a coupon");
  }

  @Test
  void manager_fixedTwentyFive_onHundred_grandIsSeventyFive() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 5, 0);

    InStoreSale sale = sell(org, product, 1, null, fixed("25"), manager, true);

    assertMoney("25.00", sale.order().getDiscountTotal());
    assertMoney("75.00", sale.order().getGrandTotal());
    assertMoney("75.00", sale.invoice().getGrandTotal());
    assertMoney("75.00", sale.payment().getAmount());
    assertEquals("FIXED", counterDiscountType(sale.order().getId()));
    assertMoney("25.00", counterDiscountValue(sale.order().getId()));
    assertNull(counterDiscountReason(sale.order().getId()), "no reason typed → NULL");
  }

  /**
   * A discount that would zero the grand is a 400 naming the cause (the coupon's rule: the payment
   * machinery assumes money moves), and it rolls everything back — no order, no stock movement, and
   * the claimed order number is un-claimed with the txn (the next sale is 00001).
   */
  @Test
  void discountThatZeroesTheGrand_is400_writesNothing_consumesNoNumber() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 5, 0);

    for (DiscountInput zeroing : List.of(fixed("100"), fixed("250"), percent("100"))) {
      ValidationException e =
          assertThrows(
              ValidationException.class, () -> sell(org, product, 1, null, zeroing, manager, true));
      assertTrue(e.getMessage().contains("exceeds the order total"), e.getMessage());
    }
    assertEquals(0, tableCount(SALES_ORDER));
    assertEquals(0, tableCount(PAYMENT));
    assertEquals(0, tableCount(INVENTORY_LOG));
    assertEquals(5, stockQty(org, product));

    InStoreSale next = sell(org, product, 1, null, null, manager, true);
    assertEquals("SO-" + YEAR + "-00001", next.order().getOrderNumber(), "no number consumed");
  }

  /**
   * PERCENT 33 on 10.00 → 3.30; and on a 0.05-tail subtotal the HALF_EVEN result is the coupon's.
   */
  @Test
  void rounding_matchesTheCouponArithmetic() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID ten = createProduct(org, "TEN", new BigDecimal("10.00"));
    UUID nickel = createProduct(org, "NICKEL", new BigDecimal("0.05"));
    createInventory(org, ten, 5, 0);
    createInventory(org, nickel, 5, 0);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Coupon save33 =
        new Coupon(
            UUID.randomUUID(),
            org,
            "SAVE33",
            CouponType.PERCENT,
            new BigDecimal("33"),
            null,
            null,
            null,
            null,
            true,
            now,
            now);
    Coupon half =
        new Coupon(
            UUID.randomUUID(),
            org,
            "HALF",
            CouponType.PERCENT,
            new BigDecimal("50"),
            null,
            null,
            null,
            null,
            true,
            now,
            now);

    InStoreSale a = sell(org, ten, 1, null, percent("33"), manager, true);
    assertMoney("3.30", a.order().getDiscountTotal());
    assertMoney("6.70", a.order().getGrandTotal());
    assertEquals(
        0, save33.discountFor(new BigDecimal("10.00")).compareTo(a.order().getDiscountTotal()));

    // 50% of 0.05 = 0.025 → HALF_EVEN 0.02 (HALF_UP would give 0.03 and a different grand).
    InStoreSale b = sell(org, nickel, 1, null, percent("50"), manager, true);
    assertMoney("0.02", b.order().getDiscountTotal());
    assertMoney("0.03", b.order().getGrandTotal());
    assertEquals(
        0, half.discountFor(new BigDecimal("0.05")).compareTo(b.order().getDiscountTotal()));
  }

  /**
   * STAFF with a discount block: refused as the approval-shaped 403 — role MANAGER, the requested
   * money named, NO threshold (there is no configurable STAFF allowance in this slice, and a
   * literal 0.00 would read "above the EGP 0.00 limit") — and nothing written: no order, payment,
   * invoice or inventory_log row, stock untouched, and no order number consumed.
   */
  @Test
  void staff_withDiscount_isApprovalRequired_writesNothing() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);

    ApprovalRequiredException e =
        assertThrows(
            ApprovalRequiredException.class,
            () -> sell(org, product, 3, null, percent("10"), staff, false));
    assertEquals(403, e.getStatusCode());
    assertEquals("MANAGER", e.getRequiredRole());
    assertNull(e.getThresholdAmount(), "no threshold on the wire — see the story");
    assertMoney("30.00", e.getRequestedAmount());

    assertEquals(0, tableCount(SALES_ORDER));
    assertEquals(0, tableCount(PAYMENT));
    assertEquals(0, tableCount(SALES_INVOICE));
    assertEquals(0, tableCount(INVENTORY_LOG));
    assertEquals(10, stockQty(org, product));

    // The gate only bites on the block: the same cashier's plain sale is the ordinary happy path,
    // and it takes the first order number — the refused request consumed none.
    InStoreSale plain = sell(org, product, 3, null, null, staff, false);
    assertEquals("CLOSED", plain.order().getStatus().name());
    assertEquals("SO-" + YEAR + "-00001", plain.order().getOrderNumber());
    assertMoney("0.00", plain.order().getDiscountTotal());
    assertMoney("300.00", plain.order().getGrandTotal());
    assertNull(counterDiscountType(plain.order().getId()));
    assertNull(counterDiscountValue(plain.order().getId()));
    assertNull(counterDiscountReason(plain.order().getId()));
    assertNull(counterDiscountBy(plain.order().getId()));
    assertNull(plain.order().getCounterDiscountType());
  }

  /** Tender is judged against the DISCOUNTED grand — both ways. */
  @Test
  void tender_isJudgedAgainstTheDiscountedGrand() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);

    // 300 handed over for a 270 sale → 30 back as an EXECUTED cash change refund.
    InStoreSale over =
        sell(org, product, 3, new BigDecimal("300.00"), percent("10"), manager, true);
    assertMoney("270.00", over.order().getGrandTotal());
    assertMoney("270.00", over.order().getPrepaidAmount());
    assertMoney("300.00", over.payment().getAmount());
    assertEquals("ALLOCATED", over.payment().getStatus().name());
    assertMoney("0.00", paymentUnallocated(over.payment().getId()));
    assertNotNull(over.changeRefund());
    assertEquals("EXECUTED", over.changeRefund().getStatus().name());
    assertMoney("30.00", over.changeRefund().getAmount());
    assertEquals(1, cashDebitTxnCount(org));

    // 260 for the same 270 → underpaid, rejected, rolled back (the first sale stays alone).
    assertThrows(
        ValidationException.class,
        () -> sell(org, product, 3, new BigDecimal("260.00"), percent("10"), manager, true));
    assertEquals(1, tableCount(SALES_ORDER));
    assertEquals(7, stockQty(org, product), "the rolled-back attempt moved no stock");
  }

  /** Shape 400s, each naming its cause, each writing nothing. */
  @Test
  void malformedDiscount_is400_writesNothing() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);

    List<DiscountInput> bad =
        List.of(
            new DiscountInput(null, new BigDecimal("10"), null),
            new DiscountInput(CouponType.PERCENT, null, null),
            percent("0"),
            percent("-5"),
            percent("101"),
            fixed("0"),
            fixed("-5"),
            new DiscountInput(CouponType.FIXED, new BigDecimal("5"), "x".repeat(201)));
    for (DiscountInput d : bad) {
      ValidationException e =
          assertThrows(
              ValidationException.class,
              () -> sell(org, product, 1, null, d, manager, true),
              "should reject " + d);
      assertTrue(e.getMessage().startsWith("discount."), e.getMessage());
    }
    assertEquals(0, tableCount(SALES_ORDER));
    assertEquals(10, stockQty(org, product));

    // Exactly 200 characters is fine.
    InStoreSale ok =
        sell(
            org,
            product,
            1,
            null,
            new DiscountInput(CouponType.FIXED, new BigDecimal("5"), "y".repeat(200)),
            manager,
            true);
    assertEquals(200, counterDiscountReason(ok.order().getId()).length());
  }

  /** The invoice's frozen contact block and the walk-in snapshot are untouched by a discount. */
  @Test
  void discount_leavesTheWalkInContactAlone() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "SKU1", new BigDecimal("100.00"));
    createInventory(org, product, 10, 0);

    InStoreSale sale =
        service.placeInStoreSale(
            org,
            new CustomerInput("Mohamed gamal", null, "01006123584", null),
            List.of(new OrderLineInput(product, 1)),
            new PaymentInput(PaymentProvider.CASH, null, null),
            percent("10"),
            null,
            actor(manager),
            UUID.randomUUID().toString(),
            manager,
            true);
    assertNull(sale.order().getCustomerId());
    assertEquals("Mohamed gamal", sale.order().getCustomerName());
    assertEquals("01006123584", sale.order().getCustomerPhone());
    assertMoney("90.00", sale.order().getGrandTotal());
    assertEquals("Mohamed gamal", invoiceCustomerName(sale.invoice().getId()));
  }

  // seed helpers

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

  // query helpers

  /** The V88 intent columns as persisted, not as echoed. */
  private String counterDiscountType(UUID orderId) {
    return dsl.select(SALES_ORDER.COUNTER_DISCOUNT_TYPE)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.COUNTER_DISCOUNT_TYPE);
  }

  private BigDecimal counterDiscountValue(UUID orderId) {
    return dsl.select(SALES_ORDER.COUNTER_DISCOUNT_VALUE)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.COUNTER_DISCOUNT_VALUE);
  }

  private String counterDiscountReason(UUID orderId) {
    return dsl.select(SALES_ORDER.COUNTER_DISCOUNT_REASON)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.COUNTER_DISCOUNT_REASON);
  }

  private UUID counterDiscountBy(UUID orderId) {
    return dsl.select(SALES_ORDER.COUNTER_DISCOUNT_BY)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.COUNTER_DISCOUNT_BY);
  }

  private BigDecimal invoiceDiscount(UUID invoiceId) {
    return dsl.select(SALES_INVOICE.DISCOUNT_TOTAL)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(invoiceId))
        .fetchOne(SALES_INVOICE.DISCOUNT_TOTAL);
  }

  private String invoiceCustomerName(UUID invoiceId) {
    return dsl.select(SALES_INVOICE.CUSTOMER_NAME)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(invoiceId))
        .fetchOne(SALES_INVOICE.CUSTOMER_NAME);
  }

  private BigDecimal paymentUnallocated(UUID paymentId) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
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
