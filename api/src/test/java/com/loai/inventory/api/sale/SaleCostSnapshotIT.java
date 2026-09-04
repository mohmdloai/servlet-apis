package com.loai.inventory.api.sale;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.PaymentProvider;
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
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.SalesOrderService.StorefrontLineInput;
import com.loai.inventory.service.SalesOrderService.StorefrontPlaced;
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
 * The V92 cost snapshot on a sale line ({@code stories/product_cost_and_margin.md}): every
 * channel's lines are built by one {@code SalesOrderService} builder from one {@code
 * ProductSnapshot}, so this pins that the product's {@code cost_price} at the moment of placement
 * lands on {@code sales_order_line.unit_cost} verbatim — in-store and storefront alike — and that a
 * later cost edit does not touch it. Same harness as {@link CounterDiscountIT}.
 */
@Testcontainers
class SaleCostSnapshotIT {

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

  @Test
  void inStoreSale_freezesTheProductsCost_andALaterEditDoesNotRewriteIt() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "NB", new BigDecimal("50.00"), new BigDecimal("30.00"));
    createInventory(org, product, 10, 0);

    InStoreSale sale = sell(org, product, 2, new BigDecimal("100.00"), staff);
    assertEquals(1, sale.lines().size());
    assertMoney("30.00", sale.lines().get(0).getUnitCost());
    assertMoney("30.00", storedUnitCost(sale.order().getId()));
    // The customer-facing money is untouched by the cost.
    assertMoney("100.00", sale.lines().get(0).getLineSubtotal());

    // Re-cost the product: yesterday's sale keeps yesterday's cost.
    dsl.update(PRODUCT)
        .set(PRODUCT.COST_PRICE, new BigDecimal("35.00"))
        .where(PRODUCT.ID.eq(product))
        .execute();
    assertMoney("30.00", storedUnitCost(sale.order().getId()));

    // And the next sale freezes the new cost.
    InStoreSale next = sell(org, product, 1, new BigDecimal("50.00"), staff);
    assertMoney("35.00", storedUnitCost(next.order().getId()));
  }

  @Test
  void inStoreSale_ofAnUncostedProduct_freezesNull_notZero() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "PEN", new BigDecimal("5.00"), null);
    createInventory(org, product, 10, 0);

    InStoreSale sale = sell(org, product, 3, new BigDecimal("15.00"), staff);
    assertNull(sale.lines().get(0).getUnitCost());
    assertNull(storedUnitCost(sale.order().getId()));
  }

  @Test
  void storefrontOrder_freezesTheCostToo_oneBuilderEveryChannel() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "NB", new BigDecimal("50.00"), new BigDecimal("30.00"));
    UUID uncosted = createProduct(org, "PEN", new BigDecimal("5.00"), null);
    createInventory(org, product, 10, 0);
    createInventory(org, uncosted, 10, 0);

    // The storefront overrides the unit PRICE (the published sales_price the shopper saw) and the
    // description; the cost has no override — what a unit cost the merchant is never the caller's
    // to say.
    StorefrontPlaced placed =
        service.placeStorefrontOrder(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(
                new StorefrontLineInput(product, 2, new BigDecimal("59.00"), "Notebook A5"),
                new StorefrontLineInput(uncosted, 1, new BigDecimal("6.00"), "Pen")),
            UUID.randomUUID().toString(),
            null,
            null,
            null,
            ActorContext.service("storefront"));

    var byProduct =
        placed.lines().stream()
            .collect(java.util.stream.Collectors.toMap(l -> l.getProductId(), l -> l));
    assertMoney("59.00", byProduct.get(product).getUnitPrice());
    assertMoney("30.00", byProduct.get(product).getUnitCost());
    assertNull(byProduct.get(uncosted).getUnitCost());
    // Persisted, not just echoed.
    assertMoney(
        "30.00",
        dsl.select(SALES_ORDER_LINE.UNIT_COST)
            .from(SALES_ORDER_LINE)
            .where(
                SALES_ORDER_LINE
                    .SALES_ORDER_ID
                    .eq(placed.order().getId())
                    .and(SALES_ORDER_LINE.PRODUCT_ID.eq(product)))
            .fetchOne(SALES_ORDER_LINE.UNIT_COST));
  }

  // helpers

  private static void assertMoney(String expected, BigDecimal actual) {
    assertNotNull(actual, "expected " + expected + " but was null");
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
  }

  private InStoreSale sell(UUID org, UUID product, int qty, BigDecimal tender, UUID caller) {
    return service.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, qty)),
        List.of(new PaymentInput(PaymentProvider.CASH, null, tender)),
        null,
        null,
        ActorContext.user(caller.toString()),
        UUID.randomUUID().toString(),
        caller,
        false);
  }

  private BigDecimal storedUnitCost(UUID orderId) {
    return dsl.select(SALES_ORDER_LINE.UNIT_COST)
        .from(SALES_ORDER_LINE)
        .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(orderId))
        .fetchOne(SALES_ORDER_LINE.UNIT_COST);
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

  private UUID createProduct(UUID org, String sku, BigDecimal basePrice, BigDecimal costPrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, basePrice)
        .set(PRODUCT.COST_PRICE, costPrice)
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
