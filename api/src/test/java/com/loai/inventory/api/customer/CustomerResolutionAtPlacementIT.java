package com.loai.inventory.api.customer;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.Placed;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * Locks the notifications-plan "Phase 0" behavior: at order placement, an email resolves to exactly
 * one <em>org-owned</em> {@code customer} — created on the fly or merged into the existing one — so
 * downstream notifications always have a customer + email to target. No migration backs this: the
 * per-org uniqueness ({@code customer_org_email_unique}) has existed since V15 and the upsert rides
 * on it via {@code SalesOrderRepository.upsertCustomerByEmail}.
 *
 * <p>The in-store walk-in case (no email → {@code customer_id} NULL) is covered by {@code
 * InStoreSaleIT}; this class drives the online path where an email is mandatory.
 */
@Testcontainers
class CustomerResolutionAtPlacementIT {

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
            new PaymentRepositoryFactoryImpl(),
            invoiceService,
            refundService,
            reservationService);
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    com.loai.inventory.service.MagicLinkService magicLink =
        new com.loai.inventory.service.MagicLinkService(
            dsl,
            new com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl(),
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
            new NotificationService(
                dsl,
                new NotificationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.UserRepositoryFactoryImpl(),
                new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
                new com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl(),
                new com.loai.inventory.service.email.LoggingEmailSender(),
                magicLink,
                NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS),
            magicLink);
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

  /** New email → a customer is created on the fly and the order points at it. */
  @Test
  void onlineOrder_newEmail_createsCustomerOnTheFly() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    Placed placed =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", "0100", null),
            List.of(new OrderLineInput(product, 2)),
            UUID.randomUUID().toString(),
            null,
            actor(staff));

    assertNotNull(placed.order().getCustomerId());
    assertEquals(1, customerCount(org));
    assertEquals(placed.customer().getId(), placed.order().getCustomerId());
    assertEquals("nadia@acme.test", customerEmail(placed.order().getCustomerId()));
    assertEquals("Nadia", customerName(placed.order().getCustomerId()));
  }

  /** Second order, same email/org → merges into the same customer row (no duplicate). */
  @Test
  void secondOnlineOrder_sameEmail_mergesIntoSameCustomer() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    Placed first =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staff));
    Placed second =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staff));

    assertEquals(first.order().getCustomerId(), second.order().getCustomerId());
    assertEquals(1, customerCount(org), "same email must not create a second customer");
  }

  /**
   * Merge is latest-non-null-wins: an incoming non-null contact field overwrites, a null one
   * preserves the existing value (the {@code COALESCE(excluded, current)} upsert).
   */
  @Test
  void merge_latestNonNullContactWins_nullsPreserveExisting() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    // First: phone set, address absent.
    service.placeOnlineOrder(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", "0100", null),
        List.of(new OrderLineInput(product, 1)),
        UUID.randomUUID().toString(),
        null,
        actor(staff));
    // Second: new name + address, phone omitted (null).
    Placed second =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia Updated", "nadia@acme.test", null, "12 Nile St"),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staff));

    UUID cid = second.order().getCustomerId();
    assertEquals(1, customerCount(org));
    assertEquals("Nadia Updated", customerName(cid), "non-null incoming name overwrites");
    assertEquals("0100", customerPhone(cid), "null incoming phone preserves existing");
    assertEquals("12 Nile St", customerAddress(cid), "previously-null address is filled");
  }

  /** Email is normalized (trim + lowercase) before the conflict check, so variants merge. */
  @Test
  void emailNormalized_caseAndWhitespace_mergeMatches() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    Placed first =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia", "Nadia@Acme.test", null, null),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staff));
    Placed second =
        service.placeOnlineOrder(
            org,
            new CustomerInput("Nadia", "  nadia@acme.test  ", null, null),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staff));

    assertEquals(first.order().getCustomerId(), second.order().getCustomerId());
    assertEquals(1, customerCount(org));
    assertEquals("nadia@acme.test", customerEmail(first.order().getCustomerId()));
  }

  /** The same email in two orgs is two distinct customers — merge is strictly per-org. */
  @Test
  void sameEmail_differentOrgs_areDistinctCustomers() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("globex");
    UUID staffA = createUser("a@acme.test");
    UUID staffB = createUser("b@globex.test");
    UUID productA = createProduct(orgA, "A");
    UUID productB = createProduct(orgB, "B");
    createInventory(orgA, productA, 5, 0);
    createInventory(orgB, productB, 5, 0);

    Placed a =
        service.placeOnlineOrder(
            orgA,
            new CustomerInput("Shared", "shared@example.test", null, null),
            List.of(new OrderLineInput(productA, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staffA));
    Placed b =
        service.placeOnlineOrder(
            orgB,
            new CustomerInput("Shared", "shared@example.test", null, null),
            List.of(new OrderLineInput(productB, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staffB));

    org.junit.jupiter.api.Assertions.assertNotEquals(
        a.order().getCustomerId(), b.order().getCustomerId());
    assertEquals(1, customerCount(orgA));
    assertEquals(1, customerCount(orgB));
  }

  /** Placing an online order fires ORDER_PLACED to org staff (the one wired Phase-1 event). */
  @Test
  void onlineOrder_notifiesOrgStaff() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, staff)
        .set(USER_ORG_ROLE.ORG_ID, org)
        .set(USER_ORG_ROLE.ROLE, com.loai.inventory.repository.generated.enums.OrgRole.STAFF)
        .execute();
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    service.placeOnlineOrder(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, null),
        List.of(new OrderLineInput(product, 1)),
        UUID.randomUUID().toString(),
        null,
        actor(staff));

    int notifications =
        dsl.fetchCount(
            dsl.selectFrom(NOTIFICATION)
                .where(NOTIFICATION.ORG_ID.eq(org))
                .and(NOTIFICATION.TYPE.eq("ORDER_PLACED"))
                .and(NOTIFICATION.RECIPIENT_USER_ID.eq(staff)));
    assertEquals(1, notifications, "the one staff member gets an ORDER_PLACED notification");
  }

  /** Online path requires an email — a blank/absent one is a 400, and nothing is persisted. */
  @Test
  void onlineOrder_missingEmail_rejected() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);

    assertThrows(
        ValidationException.class,
        () ->
            service.placeOnlineOrder(
                org,
                new CustomerInput("Nadia", null, null, null),
                List.of(new OrderLineInput(product, 1)),
                UUID.randomUUID().toString(),
                null,
                actor(staff)));
    assertEquals(0, customerCount(org));
  }

  /**
   * Empirical proof of the "no backorder" gap: ordering more than is in stock is a hard 409 ({@link
   * InsufficientStockException} extends {@code ConflictException}) and the whole placement rolls
   * back — no order, no reservation, reserved_qty untouched. There is no backorder/queue row
   * because no such concept exists.
   */
  @Test
  void onlineOrder_insufficientStock_isHard409_rollsBack_noBackorder() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 5, 0); // only 5 in stock

    assertThrows(
        InsufficientStockException.class,
        () ->
            service.placeOnlineOrder(
                org,
                new CustomerInput("Nadia", "nadia@acme.test", null, null),
                List.of(new OrderLineInput(product, 6)), // want 6 > 5
                UUID.randomUUID().toString(),
                null,
                actor(staff)));

    // Nothing persisted: no order, no reservation, stock reservation untouched — no backorder.
    assertEquals(0, salesOrderCount(org));
    assertEquals(0, reservationCount(org));
    assertEquals(0, reservedQty(org, product));
  }

  /**
   * Per-org payment-hold window ({@code reservation.md} §Default TTL, "Configurable per-org" —
   * V47): placement stamps {@code expires_at = placed_at + org.order_ttl_minutes}. Two orgs with
   * different settings get different windows; an untouched org keeps the 1440-minute (24h) default,
   * so pre-V47 behavior is unchanged.
   */
  @Test
  void reservationTtl_isPerOrg_defaultStays24h() {
    UUID orgA = createOrg("acme"); // flash-sale org: 2h window
    UUID orgB = createOrg("globex"); // B2B org: 72h window
    UUID orgC = createOrg("initech"); // untouched: default 1440
    setOrderTtlMinutes(orgA, 120);
    setOrderTtlMinutes(orgB, 4320);
    UUID staffA = createUser("a@acme.test");
    UUID staffB = createUser("b@globex.test");
    UUID staffC = createUser("c@initech.test");
    UUID pa = createProduct(orgA, "A");
    UUID pb = createProduct(orgB, "B");
    UUID pc = createProduct(orgC, "C");
    createInventory(orgA, pa, 10, 0);
    createInventory(orgB, pb, 10, 0);
    createInventory(orgC, pc, 10, 0);

    UUID orderA = placeOne(orgA, staffA, pa, "s@a.test");
    UUID orderB = placeOne(orgB, staffB, pb, "s@b.test");
    UUID orderC = placeOne(orgC, staffC, pc, "s@c.test");

    assertEquals(Duration.ofMinutes(120), holdWindow(orderA), "flash-sale org gets its 2h window");
    assertEquals(Duration.ofMinutes(4320), holdWindow(orderB), "B2B org gets its 72h window");
    assertEquals(Duration.ofHours(24), holdWindow(orderC), "untouched org keeps the 24h default");
  }

  /**
   * The knob's real path is {@code PUT /api/orgs/{orgId}} → {@link OrgService#update}: a value
   * inside the V47 bounds sticks and immediately drives placement; out-of-bounds values are a 400
   * (insta-expiring / immortal orders are impossible to configure).
   */
  @Test
  void orderTtl_updatableViaOrgService_boundsEnforced() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 0);
    OrgService orgService =
        new OrgService(
            dsl,
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.UserRepositoryFactoryImpl(),
            null);

    assertEquals(1440, orgService.getById(org).getOrderTtlMinutes(), "V47 default");

    orgService.update(org, "acme", null, 60);
    assertEquals(60, orgService.getById(org).getOrderTtlMinutes());
    UUID orderId = placeOne(org, staff, product, "s@a.test");
    assertEquals(Duration.ofMinutes(60), holdWindow(orderId));

    assertThrows(ValidationException.class, () -> orgService.update(org, "acme", null, 14));
    assertThrows(ValidationException.class, () -> orgService.update(org, "acme", null, 43_201));
    assertEquals(60, orgService.getById(org).getOrderTtlMinutes(), "rejected values don't stick");
  }

  private UUID placeOne(UUID org, UUID staff, UUID product, String email) {
    return service
        .placeOnlineOrder(
            org,
            new CustomerInput("Shared", email, null, null),
            List.of(new OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            actor(staff))
        .order()
        .getId();
  }

  private void setOrderTtlMinutes(UUID org, int minutes) {
    dsl.update(ORG).set(ORG.ORDER_TTL_MINUTES, minutes).where(ORG.ID.eq(org)).execute();
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

  // query helpers

  private int customerCount(UUID org) {
    return dsl.fetchCount(dsl.selectFrom(CUSTOMER).where(CUSTOMER.ORG_ID.eq(org)));
  }

  private int salesOrderCount(UUID org) {
    return dsl.fetchCount(dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ORG_ID.eq(org)));
  }

  private int reservationCount(UUID org) {
    return dsl.fetchCount(
        dsl.selectFrom(INVENTORY_RESERVATION).where(INVENTORY_RESERVATION.ORG_ID.eq(org)));
  }

  private int reservedQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.RESERVED_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.RESERVED_QTY);
  }

  private Duration holdWindow(UUID orderId) {
    OffsetDateTime placed =
        dsl.select(SALES_ORDER.PLACED_AT)
            .from(SALES_ORDER)
            .where(SALES_ORDER.ID.eq(orderId))
            .fetchOne(SALES_ORDER.PLACED_AT);
    OffsetDateTime expires =
        dsl.select(SALES_ORDER.EXPIRES_AT)
            .from(SALES_ORDER)
            .where(SALES_ORDER.ID.eq(orderId))
            .fetchOne(SALES_ORDER.EXPIRES_AT);
    return Duration.between(placed, expires);
  }

  private String customerEmail(UUID id) {
    return dsl.select(CUSTOMER.EMAIL)
        .from(CUSTOMER)
        .where(CUSTOMER.ID.eq(id))
        .fetchOne(CUSTOMER.EMAIL);
  }

  private String customerName(UUID id) {
    return dsl.select(CUSTOMER.NAME)
        .from(CUSTOMER)
        .where(CUSTOMER.ID.eq(id))
        .fetchOne(CUSTOMER.NAME);
  }

  private String customerPhone(UUID id) {
    return dsl.select(CUSTOMER.PHONE)
        .from(CUSTOMER)
        .where(CUSTOMER.ID.eq(id))
        .fetchOne(CUSTOMER.PHONE);
  }

  private String customerAddress(UUID id) {
    return dsl.select(CUSTOMER.ADDRESS)
        .from(CUSTOMER)
        .where(CUSTOMER.ID.eq(id))
        .fetchOne(CUSTOMER.ADDRESS);
  }
}
