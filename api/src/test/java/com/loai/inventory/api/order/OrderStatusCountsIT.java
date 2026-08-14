package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.OrderStatusCounts;
import com.loai.inventory.service.SalesOrderService.Placed;
import com.loai.inventory.service.email.LoggingEmailSender;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
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
 * Integration coverage for {@code GET /sales-orders/status-counts} ({@code
 * stories/order_status_counts.md}) — the drift pins: a chip equals its tab's list {@code total},
 * all eight statuses are always on the map ({@code 0} included), {@code total} equals both the
 * unfiltered ledger's total and Σ counts, and a second org's orders never leak.
 *
 * <p>Drives the services directly against the real jOOQ repositories — no Tomcat. VIEWER
 * authorization, the 405, and the fixed-segment routing are enforced in the handler ({@code
 * OrderStatusCountsHandlerAuthTest}), as everywhere.
 */
@Testcontainers
class OrderStatusCountsIT {

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
            TestWiring.notificationService(dsl),
            TestWiring.magicLinkService(dsl));
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            TestWiring.notificationService(dsl),
            TestWiring.magicLinkService(dsl),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    MagicLinkService magicLink =
        new MagicLinkService(
            dsl,
            new CustomerMagicTokenRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofDays(30));
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
            new NotificationService(
                dsl,
                new NotificationRepositoryFactoryImpl(),
                new UserRepositoryFactoryImpl(),
                new CustomerRepositoryFactoryImpl(),
                new NotificationPreferenceRepositoryFactoryImpl(),
                new OrgRepositoryFactoryImpl(),
                new OrgWhatsAppConfigRepositoryFactoryImpl(),
                new LoggingEmailSender(),
                magicLink,
                new com.loai.inventory.service.whatsapp.LoggingWhatsAppSender(),
                NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS),
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
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, refund, payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, notification, customer_magic_token, sales_order_line,"
            + " sales_order, customer, product, user_org_role, app_user, org,"
            + " order_number_counter, invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // scenarios

  @Test
  void counts_equalPerStatusListTotals_andTotalEqualsLedgerAndSum() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 50);

    // Three statuses live: two PENDING_PAYMENT, one PAID, one EXPIRED.
    placeOnline(org, staff, product);
    placeOnline(org, staff, product);
    flipStatus(placeOnline(org, staff, product), OrderStatus.PAID);
    flipStatus(placeOnline(org, staff, product), OrderStatus.EXPIRED);

    OrderStatusCounts counts = service.statusCounts(org);

    // The drift pin: every chip equals the total the tab's own list read reports.
    for (OrderStatus status : OrderStatus.values()) {
      assertEquals(
          service.list(org, status, 0, 1).total(),
          counts.counts().get(status),
          "chip must equal the " + status + " tab's list total");
    }
    assertEquals(2L, counts.counts().get(OrderStatus.PENDING_PAYMENT));
    assertEquals(1L, counts.counts().get(OrderStatus.PAID));
    assertEquals(1L, counts.counts().get(OrderStatus.EXPIRED));
    assertEquals(
        service.list(org, null, 0, 1).total(),
        counts.total(),
        "total must equal the unfiltered ledger's total");
    assertEquals(
        counts.counts().values().stream().mapToLong(Long::longValue).sum(),
        counts.total(),
        "total must equal the sum of the per-status counts");
  }

  @Test
  void allEightStatuses_alwaysPresent_zerosIncluded() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    placeOnline(org, staff, product);

    OrderStatusCounts counts = service.statusCounts(org);

    assertEquals(
        OrderStatus.values().length,
        counts.counts().size(),
        "every status must be on the map — absence must never mean zero");
    for (OrderStatus status : OrderStatus.values()) {
      long expected = status == OrderStatus.PENDING_PAYMENT ? 1L : 0L;
      assertEquals(expected, counts.counts().get(status), status + " count");
    }
    assertEquals(1L, counts.total());
  }

  @Test
  void secondOrgOrders_neverLeak() {
    UUID org = createOrg("acme");
    UUID other = createOrg("globex");
    UUID staff = createUser("staff@acme.test");
    UUID productA = createProduct(org, "SKU1");
    UUID productB = createProduct(other, "SKU2");
    createInventory(org, productA, 10);
    createInventory(other, productB, 10);
    placeOnline(org, staff, productA);
    placeOnline(other, staff, productB);
    placeOnline(other, staff, productB);

    assertEquals(1L, service.statusCounts(org).total(), "acme sees only its own order");
    assertEquals(2L, service.statusCounts(other).total(), "globex sees only its own two");
  }

  // helpers (mirror OrderLookupByNumberIT)

  private UUID placeOnline(UUID org, UUID staff, UUID product) {
    Placed placed =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            ActorContext.user(staff.toString()));
    return placed.order().getId();
  }

  /** Force a placed order into a target status — the counts read cares about rows, not paths. */
  private void flipStatus(UUID orderId, OrderStatus status) {
    dsl.update(SALES_ORDER)
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(status.name()))
        .where(SALES_ORDER.ID.eq(orderId))
        .execute();
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

  private void createInventory(UUID org, UUID product, int stockQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
  }
}
