package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.OrderChannel;
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
import com.loai.inventory.service.SalesOrderService.OrderListPage;
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
 * Integration coverage for {@code GET /sales-orders?q=} ({@code stories/order_search.md}) — the
 * three legs of the one free-text field on the Orders worklist: the order number (contains,
 * case-insensitive), the customer's name (folded on BOTH sides, CRM row or walk-in contact) and the
 * customer's phone (digits — CRM {@code phone_e164} or the walk-in {@code customer_phone} as typed,
 * Arabic-Indic digits folded); {@code q} composes with {@code status} and {@code channel}; rows and
 * total share one predicate; a second org's orders never match.
 *
 * <p>Drives the service directly against the real jOOQ repositories — no Tomcat. VIEWER
 * authorization and the routing are enforced in the handler, as everywhere.
 */
@Testcontainers
class OrderSearchIT {

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
            new InventoryReservationRepositoryFactoryImpl(),
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
  void number_findsByContainsCaseInsensitive_rowsAndTotalAgree() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 50);
    UUID first = placeOnline(org, staff, product, "Nadia", null);
    placeOnline(org, staff, product, "Omar", null);
    String number = numberOf(first);
    String tail = number.substring(number.length() - 4); // "0001" of SO-2026-00001

    OrderListPage page = service.list(org, null, null, tail, 0, 20);
    assertEquals(1, page.items().size(), "the number tail finds exactly its order");
    assertEquals(first, page.items().get(0).order().getId());
    assertEquals(page.items().size(), page.total(), "rows and total share one predicate");
    assertEquals(
        1,
        service.list(org, null, null, number.toLowerCase(), 0, 20).items().size(),
        "case-insensitive");
    assertEquals(
        2, service.list(org, null, null, "so-", 0, 20).items().size(), "a prefix finds them all");
  }

  @Test
  void crmName_foldsArabicSpellings_bothSides() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 50);
    UUID ahmed = placeOnline(org, staff, product, "أحمد محمود", "+201001234567");
    placeOnline(org, staff, product, "Mona Adel", "+201009876543");

    // Bare alef finds the hamza-seated alef, and vice versa — the V62 rule on both sides.
    assertEquals(List.of(ahmed), ids(service.list(org, null, null, "احمد", 0, 20)));
    assertEquals(List.of(ahmed), ids(service.list(org, null, null, "أحمد", 0, 20)));
    assertEquals(List.of(ahmed), ids(service.list(org, null, null, "محمود", 0, 20)));
    assertEquals(1L, service.list(org, null, null, "احمد", 0, 20).total());
    assertTrue(ids(service.list(org, null, null, "mona", 0, 20)).size() == 1, "Latin casefolds");
  }

  @Test
  void phone_matchesOnDigits_crmE164_andWalkInAsTyped() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 50);
    UUID crm =
        placeOnline(org, staff, product, "Nadia", "0100 123 4567"); // stored E.164 +201001234567
    UUID walkIn = placeOnline(org, staff, product, "Ignored", null);
    makeWalkIn(walkIn, "Hossam Ali", "011-2233-4455");
    placeOnline(org, staff, product, "Omar", "+201555555555");

    // Spaces, a leading 0 or a +20 — the digits are what match.
    assertEquals(List.of(crm), ids(service.list(org, null, null, "0100 123 4567", 0, 20)));
    assertEquals(List.of(crm), ids(service.list(org, null, null, "1001234567", 0, 20)));
    assertEquals(List.of(walkIn), ids(service.list(org, null, null, "22334455", 0, 20)));
    assertEquals(List.of(walkIn), ids(service.list(org, null, null, "011 2233", 0, 20)));
    // Arabic-Indic digits fold before matching.
    assertEquals(List.of(walkIn), ids(service.list(org, null, null, "٢٢٣٣٤٤", 0, 20)));
  }

  @Test
  void walkInName_isSearchable_withoutACrmRow() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 50);
    UUID walkIn = placeOnline(org, staff, product, "Ignored", null);
    makeWalkIn(walkIn, "سارة عبدالله", null);
    placeOnline(org, staff, product, "Nadia", null);

    assertEquals(List.of(walkIn), ids(service.list(org, null, null, "ساره", 0, 20)));
    assertEquals(List.of(walkIn), ids(service.list(org, null, null, "عبد", 0, 20)));
  }

  @Test
  void q_composesWithStatusAndChannel_andBlankIsAbsent() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 50);
    UUID paid = placeOnline(org, staff, product, "Nadia", null);
    UUID pending = placeOnline(org, staff, product, "Nadia", null);
    flipStatus(paid, OrderStatus.PAID);

    assertEquals(List.of(paid), ids(service.list(org, OrderStatus.PAID, null, "nadia", 0, 20)));
    assertEquals(
        List.of(pending),
        ids(service.list(org, OrderStatus.PENDING_PAYMENT, OrderChannel.ONLINE, "nadia", 0, 20)));
    assertEquals(
        0,
        service.list(org, null, OrderChannel.IN_STORE, "nadia", 0, 20).items().size(),
        "channel still narrows");
    assertEquals(2, service.list(org, null, null, "   ", 0, 20).items().size(), "blank = absent");
    assertEquals(0, service.list(org, null, null, "zzz", 0, 20).items().size());
    assertEquals(0L, service.list(org, null, null, "zzz", 0, 20).total());
  }

  @Test
  void otherOrgsOrders_neverMatch() {
    UUID org = createOrg("acme");
    UUID other = createOrg("globex");
    UUID staff = createUser("staff@acme.test");
    UUID productA = createProduct(org, "SKU1");
    UUID productB = createProduct(other, "SKU2");
    createInventory(org, productA, 10);
    createInventory(other, productB, 10);
    placeOnline(other, staff, productB, "Nadia", "+201001234567");

    assertEquals(0, service.list(org, null, null, "nadia", 0, 20).items().size());
    assertEquals(0, service.list(org, null, null, "1001234567", 0, 20).items().size());
    assertEquals(1, service.list(other, null, null, "nadia", 0, 20).items().size());
  }

  // helpers (mirror OrderStatusCountsIT)

  private UUID placeOnline(UUID org, UUID staff, UUID product, String name, String phone) {
    Placed placed =
        service.placeOnlineOrder(
            org,
            new CustomerInput(name, UUID.randomUUID() + "@acme.test", phone, null),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            ActorContext.user(staff.toString()));
    return placed.order().getId();
  }

  /** Turn a placed order into a walk-in sale: no CRM row, the contact frozen on the order (V87). */
  private void makeWalkIn(UUID orderId, String name, String phone) {
    dsl.update(SALES_ORDER)
        .setNull(SALES_ORDER.CUSTOMER_ID)
        .set(SALES_ORDER.CUSTOMER_NAME, name)
        .set(SALES_ORDER.CUSTOMER_PHONE, phone)
        .where(SALES_ORDER.ID.eq(orderId))
        .execute();
  }

  private String numberOf(UUID orderId) {
    return dsl.select(SALES_ORDER.ORDER_NUMBER)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.ORDER_NUMBER);
  }

  private static List<UUID> ids(OrderListPage page) {
    return page.items().stream().map(p -> p.order().getId()).toList();
  }

  /** Force a placed order into a target status — the search cares about rows, not paths. */
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
