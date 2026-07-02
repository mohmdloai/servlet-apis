package com.loai.inventory.api.sale;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT_LINE;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE_LINE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
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
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
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
 * Integration coverage for the in-store sale slice ({@code stories/in_store_sale.md}). Boots one
 * PostgreSQL container, runs all Flyway migrations, and drives {@link
 * SalesOrderService#placeInStoreSale} against the real jOOQ repository factories.
 *
 * <p>Headline property: one call runs order + DELIVERED fulfillment + payment + issued/paid invoice
 * in a single transaction, leaving a CLOSED order with stock decremented — or rolls everything
 * back.
 */
@Testcontainers
class InStoreSaleIT {

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
            new com.loai.inventory.service.RefundService(
                dsl,
                new com.loai.inventory.repository.RefundRepositoryFactoryImpl(),
                new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl(),
                new com.loai.inventory.repository.OrgRepositoryFactoryImpl()),
            new com.loai.inventory.service.ReservationService(
                new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl()));
    PaymentService paymentService =
        new PaymentService(
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl());
    service =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            new com.loai.inventory.service.NotificationService(
                dsl,
                new com.loai.inventory.repository.NotificationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.UserRepositoryFactoryImpl()));
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

  // ───────────────────────────── scenarios ─────────────────────────────

  /**
   * Happy path (cash, single line): CLOSED order, PAID invoice, ALLOCATED payment, stock dropped.
   */
  @Test
  void cashSale_closesOrder_paysInvoice_allocatesPayment_decrementsStock() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    InStoreSale sale =
        service.placeInStoreSale(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(new OrderLineInput(product, 3)),
            new PaymentInput(PaymentProvider.CASH, null, null),
            "counter sale",
            actor(staff),
            UUID.randomUUID().toString(),
            staff);

    // Order DRAFT → PAID → CLOSED, prepaid = grand total.
    assertEquals("CLOSED", sale.order().getStatus().name());
    assertEquals(0, new BigDecimal("30.00").compareTo(sale.order().getGrandTotal()));
    assertEquals(0, new BigDecimal("30.00").compareTo(sale.order().getPrepaidAmount()));
    assertEquals("CLOSED", orderStatus(sale.order().getId()));

    // Invoice ISSUED→PAID, gapless number, fully paid; one line.
    assertEquals("PAID", sale.invoice().getStatus().name());
    assertEquals(
        "INV-" + java.time.Year.now().getValue() + "-0001", sale.invoice().getInvoiceNumber());
    assertEquals(0, new BigDecimal("30.00").compareTo(sale.invoice().getPaidAmount()));
    assertEquals(1, invoiceLineCount(sale.invoice().getId()));

    // One allocation; payment ALLOCATED, nothing unallocated.
    assertEquals(1, sale.allocations().size());
    assertEquals("ALLOCATED", sale.payment().getStatus().name());
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(sale.payment().getId())));

    // Fulfillment DELIVERED, its line has NO reservation (in-store).
    assertEquals("DELIVERED", sale.fulfillment().getStatus().name());
    assertNotNull(sale.fulfillment().getDeliveredAt());
    assertNull(fulfillmentLineReservationId(sale.fulfillment().getId()));

    // Stock decremented (reserved untouched), exactly one SOLD log row.
    assertEquals(7, stockQty(org, product));
    assertEquals(0, reservedQty(org, product));
    assertEquals(1, soldLogCount(org, product));
  }

  /** Multi-line, in-store InstaPay: both products decrement; one invoice with two lines. */
  @Test
  void instaPayInStore_multiLine_oneInvoiceTwoLines() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    createInventory(org, a, 5, 0);
    createInventory(org, b, 5, 0);

    InStoreSale sale =
        service.placeInStoreSale(
            org,
            new CustomerInput("Omar", "omar@acme.test", null, null),
            List.of(new OrderLineInput(a, 2), new OrderLineInput(b, 1)),
            new PaymentInput(PaymentProvider.INSTAPAY_IN_STORE, "IPN-INSTORE-1", null),
            null,
            actor(staff),
            UUID.randomUUID().toString(),
            staff);

    assertEquals("CLOSED", sale.order().getStatus().name());
    assertEquals(0, new BigDecimal("30.00").compareTo(sale.invoice().getGrandTotal()));
    assertEquals("PAID", sale.invoice().getStatus().name());
    assertEquals(2, invoiceLineCount(sale.invoice().getId()));
    assertEquals(3, stockQty(org, a));
    assertEquals(4, stockQty(org, b));
  }

  /** Walk-in (no customer block): order customer_id NULL, invoice snapshot "Walk-in customer". */
  @Test
  void walkIn_noCustomer_nullCustomerId_walkInInvoiceSnapshot() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 5, 0);

    InStoreSale sale =
        service.placeInStoreSale(
            org,
            null,
            List.of(new OrderLineInput(product, 1)),
            new PaymentInput(PaymentProvider.CASH, null, null),
            null,
            actor(staff),
            UUID.randomUUID().toString(),
            staff);

    assertEquals("CLOSED", sale.order().getStatus().name());
    assertNull(sale.order().getCustomerId());
    assertEquals("Walk-in customer", invoiceCustomerName(sale.invoice().getId()));
  }

  /** Out of stock on any line → 409 InsufficientStock; the whole transaction rolls back. */
  @Test
  void outOfStock_rollsBackEverything() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 2, 0);

    assertThrows(
        InsufficientStockException.class,
        () ->
            service.placeInStoreSale(
                org,
                new CustomerInput("Sara", "sara@acme.test", null, null),
                List.of(new OrderLineInput(product, 5)),
                new PaymentInput(PaymentProvider.CASH, null, null),
                null,
                actor(staff),
                UUID.randomUUID().toString(),
                staff));

    // Nothing persisted; stock unchanged.
    assertEquals(0, tableCount(SALES_ORDER));
    assertEquals(0, tableCount(PAYMENT));
    assertEquals(0, tableCount(FULFILLMENT));
    assertEquals(2, stockQty(org, product));
  }

  /** INSTAPAY_MANUAL is the online path — rejected for in-store with 400. */
  @Test
  void instaPayManual_isRejected() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 5, 0);

    assertThrows(
        ValidationException.class,
        () ->
            service.placeInStoreSale(
                org,
                new CustomerInput("Lina", "lina@acme.test", null, null),
                List.of(new OrderLineInput(product, 1)),
                new PaymentInput(PaymentProvider.INSTAPAY_MANUAL, "IPN-1", null),
                null,
                actor(staff),
                UUID.randomUUID().toString(),
                staff));
    assertEquals(0, tableCount(SALES_ORDER));
  }

  /** A non-exact tender (amount ≠ grand total) is rejected; v1 is exact-tender only. */
  @Test
  void nonExactTender_isRejected_rollsBack() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 5, 0);

    assertThrows(
        ValidationException.class,
        () ->
            service.placeInStoreSale(
                org,
                new CustomerInput("Hana", "hana@acme.test", null, null),
                List.of(new OrderLineInput(product, 3)), // grand total 30.00
                new PaymentInput(PaymentProvider.CASH, null, new BigDecimal("999.00")),
                null,
                actor(staff),
                UUID.randomUUID().toString(),
                staff));
    assertEquals(0, tableCount(SALES_ORDER));
    assertEquals(0, tableCount(PAYMENT));
    assertEquals(5, stockQty(org, product));
  }

  /**
   * A retried checkout with the same idempotency key is rejected (409) — no double-charge, no
   * second stock decrement. Exercises both double-submit barriers: the order {@code (org_id,
   * idempotency_key)} UNIQUE and the cash payment {@code (provider, provider_ref)} UNIQUE.
   */
  @Test
  void retriedCashSale_sameIdempotencyKey_isRejected_noDoubleCharge() {
    UUID org = createOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);
    String idem = UUID.randomUUID().toString();

    InStoreSale first =
        service.placeInStoreSale(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(new OrderLineInput(product, 3)),
            new PaymentInput(PaymentProvider.CASH, null, null),
            null,
            actor(staff),
            idem,
            staff);
    assertEquals("CLOSED", first.order().getStatus().name());

    assertThrows(
        ConflictException.class,
        () ->
            service.placeInStoreSale(
                org,
                new CustomerInput("Nadia", "nadia@acme.test", null, null),
                List.of(new OrderLineInput(product, 3)),
                new PaymentInput(PaymentProvider.CASH, null, null),
                null,
                actor(staff),
                idem,
                staff));

    // Exactly one sale persisted; stock decremented once (10 → 7), not twice.
    assertEquals(1, tableCount(SALES_ORDER));
    assertEquals(1, tableCount(PAYMENT));
    assertEquals(7, stockQty(org, product));
  }

  // ───────────────────────────── seed helpers ─────────────────────────────

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

  // ───────────────────────────── query helpers ─────────────────────────────

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  private int invoiceLineCount(UUID invoiceId) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_INVOICE_LINE)
            .where(SALES_INVOICE_LINE.SALES_INVOICE_ID.eq(invoiceId)));
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

  private UUID fulfillmentLineReservationId(UUID fulfillmentId) {
    return dsl.select(FULFILLMENT_LINE.INVENTORY_RESERVATION_ID)
        .from(FULFILLMENT_LINE)
        .where(FULFILLMENT_LINE.FULFILLMENT_ID.eq(fulfillmentId))
        .fetchAny(FULFILLMENT_LINE.INVENTORY_RESERVATION_ID);
  }

  private int stockQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private int reservedQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.RESERVED_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.RESERVED_QTY);
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
}
