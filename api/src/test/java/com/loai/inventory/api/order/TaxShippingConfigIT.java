package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.SalesOrderService.Placed;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * Config-complete tax + shipping (V68, roadmap item 5, {@code stories/org_tax_shipping_config.md}):
 * an org's {@code tax_rate} is stamped onto every order line at placement and {@code shipping_fee}
 * rides the order as a scalar {@code shipping_total} (ONLINE/PHONE only — never IN_STORE, never an
 * order line). At delivery the shipping is billed on the <b>first live invoice only</b>, so the sum
 * of live invoice grand totals always equals the order grand total (the CLOSED roll-up + FIFO
 * allocator identity). Drives the real placement/payment/delivery services — no Tomcat.
 */
@Testcontainers
class TaxShippingConfigIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService salesOrderService;
  static PaymentTransactionService txnService;
  static FulfillmentService fulfillmentService;

  private final AtomicInteger refSeq = new AtomicInteger(1);
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

    MagicLinkService magicLink = TestWiring.magicLinkService(dsl);
    var notificationService = TestWiring.notificationService(dsl);
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            notificationService,
            magicLink,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    RefundService refundService =
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
    txnService =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage());
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
    fulfillmentService =
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
    salesOrderService =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            notificationService,
            magicLink,
            TestWiring.permissiveEmailGate(),
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
        "TRUNCATE notification_preference, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery, notification,"
            + " customer_magic_token, payment_allocation, sales_invoice_line, sales_invoice,"
            + " refund, payment, payment_transaction, fulfillment_line, fulfillment,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, user_org_role, app_user, org, order_number_counter,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // scenarios

  /** Online order: 14% tax on every line + the flat shipping fee → grand total. */
  @Test
  void onlineOrder_appliesOrgTaxAndShipping() {
    UUID org = createOrg("0.1400", "25.00");
    UUID product = createProduct(org); // base_price 10.00
    createInventory(org, product, 10);

    Placed placed = placeOnline(org, product, 3);

    // subtotal 30.00, tax 4.20 (14% per line), shipping 25.00 → grand 59.20.
    assertEquals(0, new BigDecimal("30.00").compareTo(placed.order().getSubtotal()));
    assertEquals(0, new BigDecimal("4.20").compareTo(placed.order().getTaxTotal()));
    assertEquals(0, new BigDecimal("25.00").compareTo(placed.order().getShippingTotal()));
    assertEquals(0, new BigDecimal("59.20").compareTo(placed.order().getGrandTotal()));
    for (SalesOrderLine l : placed.lines()) {
      assertEquals(0, new BigDecimal("0.1400").compareTo(l.getTaxRate()));
    }

    // The full grand total (incl. tax + shipping) is what reconciles as an exact cover.
    var verified =
        txnService.verify(
            org,
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                "IPN-" + refSeq.getAndIncrement(),
                new BigDecimal("59.20"),
                "EGP",
                null,
                placed.order().getOrderNumber(),
                null,
                null,
                null,
                null),
            createUser(org));
    assertEquals("MATCHED", verified.reconciliationStatus().name());
  }

  /**
   * Partial delivery: shipping is billed on the FIRST live invoice only; the second invoice carries
   * none — and the two live invoices still sum to the order grand total, so the order CLOSES.
   */
  @Test
  void partialDelivery_billsShippingOnFirstInvoiceOnly_orderStillCloses() {
    UUID org = createOrg("0.1000", "15.00");
    UUID a = createProduct(org);
    UUID b = createProduct(org);
    createInventory(org, a, 5);
    createInventory(org, b, 5);

    // 1×10.00 + 1×10.00, 10% tax → subtotal 20, tax 2, shipping 15 → grand 37.00.
    Placed placed = placeOnline(org, List.of(new OrderLineInput(a, 1), new OrderLineInput(b, 1)));
    assertEquals(0, new BigDecimal("37.00").compareTo(placed.order().getGrandTotal()));

    txnService.verify(
        org,
        new VerifyCommand(
            PaymentProvider.INSTAPAY_MANUAL,
            "IPN-" + refSeq.getAndIncrement(),
            new BigDecimal("37.00"),
            "EGP",
            null,
            placed.order().getOrderNumber(),
            null,
            null,
            null,
            null),
        createUser(org));

    // Deliver line A → its invoice bills subtotal 10 + tax 1 + shipping 15 = 26.00.
    var invA = deliverLine(org, placed, 0);
    assertEquals(0, new BigDecimal("15.00").compareTo(invA.getShippingTotal()));
    assertEquals(0, new BigDecimal("26.00").compareTo(invA.getGrandTotal()));

    // Deliver line B → shipping already billed → 10 + 1 + 0 = 11.00; order CLOSED.
    var invB = deliverLine(org, placed, 1);
    assertEquals(0, BigDecimal.ZERO.compareTo(invB.getShippingTotal()));
    assertEquals(0, new BigDecimal("11.00").compareTo(invB.getGrandTotal()));

    assertEquals(
        "CLOSED",
        dsl.fetchOne("SELECT status FROM sales_order WHERE id = ?", placed.order().getId())
            .get(0, String.class));
    // The identity the roll-up depends on: live invoices sum to the order grand total.
    BigDecimal invoiceSum =
        dsl.fetchOne(
                "SELECT COALESCE(SUM(grand_total),0) FROM sales_invoice WHERE sales_order_id = ?"
                    + " AND status <> 'VOID'",
                placed.order().getId())
            .get(0, BigDecimal.class);
    assertEquals(0, placed.order().getGrandTotal().compareTo(invoiceSum));
  }

  /** In-store: tax applies, shipping never does (the goods walk out with the customer). */
  @Test
  void inStoreSale_appliesTax_neverShipping() {
    UUID org = createOrg("0.1400", "25.00");
    UUID staff = createUser(org);
    UUID product = createProduct(org);
    createInventory(org, product, 10);

    InStoreSale sale =
        salesOrderService.placeInStoreSale(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(new OrderLineInput(product, 3)),
            new PaymentInput(PaymentProvider.CASH, null, null),
            null,
            actor,
            UUID.randomUUID().toString(),
            staff);

    // subtotal 30.00 + tax 4.20, NO shipping → grand 34.20; invoice mirrors it.
    assertEquals(0, BigDecimal.ZERO.compareTo(sale.order().getShippingTotal()));
    assertEquals(0, new BigDecimal("34.20").compareTo(sale.order().getGrandTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(sale.invoice().getShippingTotal()));
    assertEquals(0, new BigDecimal("34.20").compareTo(sale.invoice().getGrandTotal()));
    assertEquals("CLOSED", sale.order().getStatus().name());
  }

  /** An unconfigured org (both knobs 0) reproduces the historic zero-tax free-shipping math. */
  @Test
  void unconfiguredOrg_keepsHistoricMath() {
    UUID org = createOrg(null, null); // DB defaults: 0 / 0
    UUID product = createProduct(org);
    createInventory(org, product, 10);

    Placed placed = placeOnline(org, product, 2);

    assertEquals(0, new BigDecimal("20.00").compareTo(placed.order().getSubtotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(placed.order().getTaxTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(placed.order().getShippingTotal()));
    assertEquals(0, new BigDecimal("20.00").compareTo(placed.order().getGrandTotal()));
  }

  /** The V68 CHECK mirror: tax_rate ∈ [0,1], shipping_fee ≥ 0 — cause-naming 400s. */
  @Test
  void storeConfigValidation_rejectsOutOfRangeValues() {
    assertThrows(
        ValidationException.class,
        () ->
            OrgService.validateStoreConfig(
                new OrgService.StoreConfig(new BigDecimal("1.5"), null)));
    assertThrows(
        ValidationException.class,
        () ->
            OrgService.validateStoreConfig(new OrgService.StoreConfig(null, new BigDecimal("-1"))));
    // Boundary values pass.
    OrgService.validateStoreConfig(new OrgService.StoreConfig(BigDecimal.ONE, BigDecimal.ZERO));
  }

  // flow helpers

  private Placed placeOnline(UUID org, UUID product, int qty) {
    return placeOnline(org, List.of(new OrderLineInput(product, qty)));
  }

  private Placed placeOnline(UUID org, List<OrderLineInput> lines) {
    return salesOrderService.placeOnlineOrder(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, null),
        lines,
        UUID.randomUUID().toString(),
        null,
        actor);
  }

  /** Create + ship + deliver a single-line fulfillment for the order's {@code lineIdx} line. */
  private com.loai.inventory.domain.model.SalesInvoice deliverLine(
      UUID org, Placed placed, int lineIdx) {
    UUID f =
        fulfillmentService
            .create(
                org,
                placed.order().getId(),
                List.of(new LineInput(placed.lines().get(lineIdx).getId())),
                null,
                null,
                null,
                actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, f, actor);
    return fulfillmentService.markDelivered(org, f, actor).invoice();
  }

  // entity helpers

  /** An org with the given money config (null = keep the DB defaults of 0/0). */
  private UUID createOrg(String taxRate, String shippingFee) {
    UUID id = UUID.randomUUID();
    var insert =
        dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, "acme").set(ORG.SLUG, "acme-" + id);
    if (taxRate != null) {
      insert = insert.set(ORG.TAX_RATE, new BigDecimal(taxRate));
    }
    if (shippingFee != null) {
      insert = insert.set(ORG.SHIPPING_FEE, new BigDecimal(shippingFee));
    }
    insert.execute();
    return id;
  }

  private UUID createUser(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-admin@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, "widget")
        .set(PRODUCT.SKU, "SKU-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private void createInventory(UUID org, UUID product, int stockQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
  }
}
