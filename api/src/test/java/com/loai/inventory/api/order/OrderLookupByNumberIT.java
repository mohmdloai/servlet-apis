package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.PaymentProvider;
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
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.Placed;
import com.loai.inventory.service.email.LoggingEmailSender;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Integration coverage for the order-number lookup ({@code stories/lookup_order_by_number.md}): the
 * pre-flight read for the manual money path — exact, case-sensitive, trimmed match; the full
 * placement response shape; 404 scoping.
 *
 * <p>Drives the services directly against the real jOOQ repositories — no Tomcat. VIEWER
 * authorization and the missing-param 400 are enforced in the handler ({@code
 * OrderLookupHandlerAuthTest}), as everywhere.
 */
@Testcontainers
class OrderLookupByNumberIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService service;
  static PaymentTransactionService txnService;

  private final AtomicInteger refSeq = new AtomicInteger(1);
  private final OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

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
    txnService =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage(),
            new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.UserRepositoryFactoryImpl());
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
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, refund, payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, notification, customer_magic_token, sales_order_line,"
            + " sales_order, customer, product, user_org_role, app_user, org,"
            + " order_number_counter, invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // scenarios

  @Test
  void placedOnlineOrder_foundByNumber_fullShape() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    Placed placed = placeOnline(org, staff, product, 2);
    String number = placed.order().getOrderNumber();

    Placed found = service.getByNumber(org, number);

    assertEquals(placed.order().getId(), found.order().getId());
    assertEquals(OrderStatus.PENDING_PAYMENT, found.order().getStatus());
    assertEquals(1, found.lines().size());
    assertEquals(2, found.lines().get(0).getQuantity());
    assertNotNull(found.order().getExpiresAt(), "reserved online order carries expires_at");
    assertEquals(0, BigDecimal.ZERO.compareTo(found.order().getPrepaidAmount()));
  }

  @Test
  void afterUnderpaidReconcile_previewShowsAccumulatedPrepaid() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    Placed placed = placeOnline(org, staff, product, 2); // grand total 20.00
    String number = placed.order().getOrderNumber();

    txnService.verify(
        org,
        new VerifyCommand(
            PaymentProvider.INSTAPAY_MANUAL,
            "IPN-" + refSeq.getAndIncrement(),
            new BigDecimal("5.00"),
            "EGP",
            null,
            number,
            null,
            null,
            null,
            base),
        staff);

    Placed found = service.getByNumber(org, number);

    // The pre-flight predicts the next reconcile outcome: 15.00 outstanding.
    assertEquals(OrderStatus.PENDING_PAYMENT, found.order().getStatus());
    assertEquals(0, new BigDecimal("5.00").compareTo(found.order().getPrepaidAmount()));
  }

  @Test
  void whitespacePaddedInput_matches_wrongCaseDoesNot() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    Placed placed = placeOnline(org, staff, product, 1);
    String number = placed.order().getOrderNumber();

    assertEquals(
        placed.order().getId(), service.getByNumber(org, "  " + number + "  ").order().getId());
    assertThrows(
        NotFoundException.class,
        () -> service.getByNumber(org, number.toLowerCase()),
        "order_number match is case-sensitive");
  }

  @Test
  void anotherOrgsNumber_is404_scopingNotForbidden() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("globex");
    UUID staffA = createUser("a@acme.test");
    UUID productA = createProduct(orgA, "A");
    createInventory(orgA, productA, 10);
    Placed placed = placeOnline(orgA, staffA, productA, 1);

    assertThrows(
        NotFoundException.class, () -> service.getByNumber(orgB, placed.order().getOrderNumber()));
  }

  @Test
  void missingOrBlankNumber_is400() {
    UUID org = createOrg("acme");

    assertThrows(ValidationException.class, () -> service.getByNumber(org, null));
    assertThrows(ValidationException.class, () -> service.getByNumber(org, "   "));
  }

  // lookup by id ({@code stories/fulfillment_reads.md})

  @Test
  void getById_returnsSameShapeAsNumberLookup() {
    UUID org = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);
    Placed placed = placeOnline(org, staff, product, 2);

    Placed found = service.getById(org, placed.order().getId());

    assertEquals(placed.order().getId(), found.order().getId());
    assertEquals(placed.order().getOrderNumber(), found.order().getOrderNumber());
    assertEquals(OrderStatus.PENDING_PAYMENT, found.order().getStatus());
    assertEquals(1, found.lines().size());
    assertEquals(2, found.lines().get(0).getQuantity());
  }

  @Test
  void getById_unknownOrForeignId_is404_nullId_is400() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("globex");
    UUID staffA = createUser("a@acme.test");
    UUID productA = createProduct(orgA, "A");
    createInventory(orgA, productA, 10);
    Placed placed = placeOnline(orgA, staffA, productA, 1);

    assertThrows(NotFoundException.class, () -> service.getById(orgA, UUID.randomUUID()));
    // Another org's order is invisible, not forbidden — scoping over the shared schema.
    assertThrows(NotFoundException.class, () -> service.getById(orgB, placed.order().getId()));
    assertThrows(ValidationException.class, () -> service.getById(orgA, null));
  }

  // helpers

  private Placed placeOnline(UUID org, UUID staff, UUID product, int qty) {
    return service.placeOnlineOrder(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, null),
        List.of(new OrderLineInput(product, qty)),
        UUID.randomUUID().toString(),
        null,
        ActorContext.user(staff.toString()));
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
