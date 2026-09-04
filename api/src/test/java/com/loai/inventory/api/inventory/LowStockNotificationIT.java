package com.loai.inventory.api.inventory;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.InsufficientStockException;
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
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrgRole;
import com.loai.inventory.repository.generated.tables.records.NotificationRecord;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.LowStockNotifier;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.SalesOrderService.StorefrontLineInput;
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
 * The reorder point ({@code stories/reorder_point.md}, V94): a sale that takes a product's
 * <em>available</em> stock from above its point to at or below it raises one {@code LOW_STOCK} per
 * active staff member, inside the sale's transaction — through the real placement paths (the
 * in-store sale and the online reservation) and the real {@link NotificationService}. Fires on the
 * crossing, never on the state; a restock re-arms it; a rolled-back sale tells nobody.
 */
@Testcontainers
class LowStockNotificationIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService sales;
  static InventoryService inventory;
  static LowStockNotifier notifier;

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

    NotificationService notifications =
        com.loai.inventory.api.support.TestWiring.notificationService(dsl);
    notifier = new LowStockNotifier(new ProductRepositoryFactoryImpl(), notifications);

    ReservationService reservationService =
        new ReservationService(
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            notifier);
    InvoiceService invoiceService =
        new InvoiceService(
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    com.loai.inventory.service.RefundService refundService =
        new com.loai.inventory.service.RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
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
            notifications,
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            notifier);
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            notifications,
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    sales =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            notifications,
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.CouponService(
                dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    inventory =
        new InventoryService(
            dsl,
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductRepositoryImpl(dsl),
            new SalesOrderRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE notification_delivery_in_app, notification_delivery_email,"
            + " notification_delivery, notification, payment_allocation, sales_invoice_line,"
            + " sales_invoice, payment, payment_transaction, fulfillment_line, fulfillment,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, user_org_role, app_user, org, order_number_counter,"
            + " invoice_number_counter, org_milestone RESTART IDENTITY CASCADE");
  }

  // the in-store sale

  @Test
  void inStoreSale_crossingThePoint_notifiesEveryStaffMember_withPayloadAndLink() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID manager = staff(org, OrgRole.MANAGER);
    UUID viewer = staff(org, OrgRole.VIEWER); // reads only — not in the fan-out
    UUID product = createProduct(org, "Notebook", 5);
    createInventory(org, product, 7, 0); // available 7, point 5

    sellInStore(org, cashier, product, 2); // 7 → 5: reaches the point

    List<NotificationRecord> rows = lowStockRows(org);
    assertEquals(2, rows.size(), "one per STAFF/MANAGER/OWNER member");
    assertTrue(rows.stream().anyMatch(r -> cashier.equals(r.getRecipientUserId())));
    assertTrue(rows.stream().anyMatch(r -> manager.equals(r.getRecipientUserId())));
    assertTrue(rows.stream().noneMatch(r -> viewer.equals(r.getRecipientUserId())));
    NotificationRecord row = rows.get(0);
    assertEquals("Low stock: Notebook", row.getTitle());
    assertEquals("5 left of Notebook (NB-" + product + ") — reorder point 5.", row.getBody());
    assertEquals("product", row.getSourceType());
    assertEquals(product, row.getSourceId());
    assertEquals(5, payload(row).get("available").asInt());
    assertEquals(5, payload(row).get("reorder_point").asInt());
    assertEquals(product.toString(), payload(row).get("product_id").asText());
    assertTrue(inAppLinkTarget(row.getId()).endsWith("/inventory/" + product));
  }

  @Test
  void inStoreSale_fallingWellBelow_firesOnce() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", 10);
    createInventory(org, product, 12, 0);

    sellInStore(org, cashier, product, 9); // 12 → 3, straight through the point

    assertEquals(1, lowStockRows(org).size());
  }

  @Test
  void alreadyBelow_sellsOnInSilence() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", 10);
    createInventory(org, product, 8, 0); // already below 10

    sellInStore(org, cashier, product, 1);
    sellInStore(org, cashier, product, 1);

    assertEquals(0, lowStockRows(org).size(), "the state is not the event");
  }

  @Test
  void stillAbove_isSilent() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", 3);
    createInventory(org, product, 10, 0);

    sellInStore(org, cashier, product, 2); // 10 → 8

    assertEquals(0, lowStockRows(org).size());
  }

  @Test
  void noReorderPoint_neverFires() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", null);
    createInventory(org, product, 2, 0);

    sellInStore(org, cashier, product, 2); // 2 → 0, no rule

    assertEquals(0, lowStockRows(org).size());
  }

  @Test
  void pointZero_firesOnReachingZero() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", 0);
    createInventory(org, product, 1, 0);

    sellInStore(org, cashier, product, 1); // 1 → 0

    assertEquals(1, lowStockRows(org).size());
  }

  @Test
  void restockAbove_reArms_andTheNextDescentFiresAgain() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", 5);
    createInventory(org, product, 6, 0);

    sellInStore(org, cashier, product, 1); // 6 → 5: fires
    assertEquals(1, lowStockRows(org).size());
    sellInStore(org, cashier, product, 1); // 5 → 4: silent (already below)
    assertEquals(1, lowStockRows(org).size());

    inventory.restock(org, product, 10, ActorContext.user(cashier.toString())); // 4 → 14
    assertEquals(1, lowStockRows(org).size(), "a restock is not a sale");

    sellInStore(org, cashier, product, 9); // 14 → 5: fires again
    assertEquals(2, lowStockRows(org).size());
  }

  @Test
  void twoLinesOfOneProduct_areOneCrossing_oneNotification() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", 5);
    createInventory(org, product, 8, 0);

    sales.placeInStoreSale(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, null),
        List.of(new OrderLineInput(product, 2), new OrderLineInput(product, 2)),
        List.of(new PaymentInput(PaymentProvider.CASH, null, null)),
        null,
        null,
        ActorContext.user(cashier.toString()),
        UUID.randomUUID().toString(),
        cashier,
        false);

    assertEquals(1, lowStockRows(org).size());
  }

  @Test
  void shortage_rollsTheSaleBack_andNotifiesNobody() {
    UUID org = createOrg();
    UUID cashier = staff(org, OrgRole.STAFF);
    UUID low = createProduct(org, "Pen", 5);
    UUID scarce = createProduct(org, "Ink", null);
    createInventory(org, low, 6, 0);
    createInventory(org, scarce, 0, 0);

    assertThrows(
        InsufficientStockException.class,
        () ->
            sales.placeInStoreSale(
                org,
                new CustomerInput("Nadia", "nadia@acme.test", null, null),
                List.of(new OrderLineInput(low, 2), new OrderLineInput(scarce, 1)),
                List.of(new PaymentInput(PaymentProvider.CASH, null, null)),
                null,
                null,
                ActorContext.user(cashier.toString()),
                UUID.randomUUID().toString(),
                cashier,
                false));

    assertEquals(0, lowStockRows(org).size());
    assertEquals(6, stockOf(low), "nothing moved");
  }

  // the online reservation

  @Test
  void onlinePlacement_reservationCrossing_fires_andTheOrderPlacedRowStillLands() {
    UUID org = createOrg();
    UUID staff = staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Notebook", 5);
    createInventory(org, product, 7, 0); // available 7

    sales.placeStorefrontOrder(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, null),
        List.of(new StorefrontLineInput(product, 3, new BigDecimal("59.00"), "Notebook")),
        UUID.randomUUID().toString(),
        null,
        null,
        null,
        ActorContext.service("storefront"));

    // stock 7, reserved 3 → available 4 ≤ 5: the reservation IS the sale reducing what can be sold.
    List<NotificationRecord> rows = lowStockRows(org);
    assertEquals(1, rows.size());
    assertEquals(staff, rows.get(0).getRecipientUserId());
    assertEquals(4, payload(rows.get(0)).get("available").asInt());
    // The existing fan-out is untouched: ORDER_PLACED to the one staff member and to the customer.
    assertEquals(2, rowsOfType(org, "ORDER_PLACED").size());
  }

  // the notifier's own rule, for the site that must not fire

  @Test
  void aMoveThatLeavesAvailableUnchanged_isNotACrossing() {
    UUID org = createOrg();
    staff(org, OrgRole.STAFF);
    UUID product = createProduct(org, "Pen", 5);
    createInventory(org, product, 10, 5);

    // What a ship does: stock and reserved both drop, available (5) stays — no call, and even
    // if one were made with equal before/after it would be a no-op.
    notifier.afterSale(dsl, org, List.of(new LowStockNotifier.StockMove(product, 5, 5)));
    notifier.afterSale(dsl, org, List.of(new LowStockNotifier.StockMove(product, 4, 6)));

    assertEquals(0, lowStockRows(org).size());
  }

  // fixtures

  private void sellInStore(UUID org, UUID cashier, UUID product, int qty) {
    sales.placeInStoreSale(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, null),
        List.of(new OrderLineInput(product, qty)),
        List.of(new PaymentInput(PaymentProvider.CASH, null, null)),
        null,
        null,
        ActorContext.user(cashier.toString()),
        UUID.randomUUID().toString(),
        cashier,
        false);
  }

  private List<NotificationRecord> lowStockRows(UUID org) {
    return rowsOfType(org, "LOW_STOCK");
  }

  private List<NotificationRecord> rowsOfType(UUID org, String type) {
    return dsl.selectFrom(NOTIFICATION)
        .where(NOTIFICATION.ORG_ID.eq(org).and(NOTIFICATION.TYPE.eq(type)))
        .orderBy(NOTIFICATION.CREATED_AT.asc())
        .fetch();
  }

  private static com.fasterxml.jackson.databind.JsonNode payload(NotificationRecord row) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.getPayload().data());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String inAppLinkTarget(UUID notificationId) {
    return dsl.fetchOne(
            "select ia.link_target from notification_delivery_in_app ia"
                + " join notification_delivery d on d.id = ia.delivery_id"
                + " where d.notification_id = ?",
            notificationId)
        .get(0, String.class);
  }

  private int stockOf(UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.PRODUCT_ID.eq(product))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    // English default so the template assertions read the EN branch (the org default is 'ar').
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "acme")
        .set(ORG.SLUG, "acme-" + id)
        .set(ORG.DEFAULT_LOCALE, "en")
        .execute();
    return id;
  }

  private UUID staff(UUID org, OrgRole role) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .set(APP_USER.ACTIVE, true)
        .execute();
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, id)
        .set(USER_ORG_ROLE.ORG_ID, org)
        .set(USER_ORG_ROLE.ROLE, role)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String name, Integer reorderPoint) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, name)
        .set(PRODUCT.SKU, "NB-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .set(PRODUCT.REORDER_POINT, reorderPoint)
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
