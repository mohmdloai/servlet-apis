package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER_MAGIC_TOKEN;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
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
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.email.EmailException;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * The ORDER_PAID customer notification ({@code stories/notify_order_paid.md}): produced inside the
 * reconcile transaction at both {@code markPaid} sites (MATCHED / OVERPAID) — covering verify and
 * orphan-resolve alike — delivered post-commit by the email sweeper, suppressible via Phase 3
 * preferences, and deliberately silent on UNDERPAID and the in-store sale.
 *
 * <p>Drives the services directly (no Tomcat); the sweeper is driven manually per the existing
 * notification ITs.
 */
@Testcontainers
class OrderPaidNotificationIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CapturingEmailSender emailSender;
  static NotificationService notificationService;
  static PaymentTransactionService txnService;
  static SalesOrderService salesOrderService;

  private final AtomicInteger orderSeq = new AtomicInteger(1);
  private final AtomicInteger refSeq = new AtomicInteger(1);
  private final OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

  /** Records every message it is handed; can be armed to throw. */
  static final class CapturingEmailSender implements EmailSender {
    final List<EmailMessage> captured = new ArrayList<>();
    boolean throwOnSend = false;

    @Override
    public void send(EmailMessage message) {
      if (throwOnSend) {
        throw new EmailException("simulated provider failure", null);
      }
      captured.add(message);
    }
  }

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

    emailSender = new CapturingEmailSender();
    MagicLinkService magicLink = TestWiring.magicLinkService(dsl);
    notificationService = TestWiring.notificationService(dsl, emailSender);
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
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
            TestWiring.storage(),
            new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.UserRepositoryFactoryImpl());
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
    emailSender.captured.clear();
    emailSender.throwOnSend = false;
    dsl.execute(
        "TRUNCATE notification_preference, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery, notification,"
            + " customer_magic_token, payment_allocation, sales_invoice_line, sales_invoice,"
            + " refund, payment, payment_transaction, fulfillment_line, fulfillment,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, user_org_role, app_user, org, order_number_counter,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // the happy paths

  @Test
  void exactCoverVerify_firesOnce_sweepDeliversEmailWithFreshMagicLink() {
    Seed s = seed("500.00");

    verify(s, "500.00", s.orderNumber(), "MATCHED");

    List<UUID> paid = orderPaidNotificationIds(s.orgId());
    assertEquals(1, paid.size());
    assertEquals(s.customerId(), recipientCustomerId(paid.get(0)));
    String body = notificationBody(paid.get(0));
    assertTrue(body.contains("500.00 EGP"), "body carries the amount: " + body);
    assertTrue(body.contains(s.orderNumber()), "body carries the order number: " + body);
    // A fresh VIEW_ORDER token targeting this order was minted in the same txn.
    assertEquals(1, viewOrderTokenCount(s.orgId(), s.orderId()));

    NotificationService.DeliverySummary summary = notificationService.dispatchPendingEmail(100);

    assertEquals(1, summary.sent());
    // The P5 in_app feed leg must also drain before the parent finalizes.
    notificationService.dispatchPendingInApp(100);
    assertEquals("DISPATCHED", notificationStatus(paid.get(0)));
    assertEquals(1, emailSender.captured.size());
    EmailMessage msg = emailSender.captured.get(0);
    assertEquals("nadia@acme.test", msg.to());
    assertEquals("Payment received for " + s.orderNumber(), msg.subject());
    assertTrue(msg.html().contains("/orders/"), "email carries the order-view magic link");
  }

  @Test
  void overpaidVerify_firesOnce_sameTemplate() {
    Seed s = seed("500.00");

    verify(s, "600.00", s.orderNumber(), "OVERPAID");

    List<UUID> paid = orderPaidNotificationIds(s.orgId());
    assertEquals(1, paid.size());
    assertTrue(notificationBody(paid.get(0)).contains("600.00 EGP"));
  }

  @Test
  void underpaid_noNotification_exactTopUpFiresExactlyOne() {
    Seed s = seed("1000.00");

    verify(s, "400.00", s.orderNumber(), "UNDERPAID");
    assertEquals(0, orderPaidNotificationIds(s.orgId()).size(), "partial payment must not fire");

    verify(s, "600.00", s.orderNumber(), "MATCHED");
    assertEquals(1, orderPaidNotificationIds(s.orgId()).size(), "the completing top-up fires once");
  }

  @Test
  void orphanResolution_reusesReconcile_fires() {
    Seed s = seed("300.00");
    UUID orphan = verify(s, "300.00", null, "ORPHAN");
    assertEquals(0, orderPaidNotificationIds(s.orgId()).size());

    txnService.resolveOrphan(s.orgId(), orphan, new OrderRef(null, s.orderNumber()), s.admin());

    assertEquals(1, orderPaidNotificationIds(s.orgId()).size());
    assertEquals("PAID", orderStatus(s.orderId()));
  }

  // the silences

  @Test
  void currencyMismatch_rollsBackWholeTxn_noNotificationRow() {
    Seed s = seed("500.00");

    assertThrows(
        ValidationException.class,
        () ->
            txnService.verify(
                s.orgId(),
                new VerifyCommand(
                    PaymentProvider.INSTAPAY_MANUAL,
                    "IPN-MISMATCH",
                    new BigDecimal("500.00"),
                    "USD",
                    null,
                    s.orderNumber(),
                    null,
                    null,
                    null,
                    base),
                s.admin()));

    assertEquals(0, orderPaidNotificationIds(s.orgId()).size(), "rollback leaves no notification");
    assertEquals(
        0,
        dsl.fetchCount(
            dsl.selectFrom(PAYMENT_TRANSACTION).where(PAYMENT_TRANSACTION.ORG_ID.eq(s.orgId()))),
        "rollback leaves no transaction either");
  }

  @Test
  void idempotentVerifyReplay_noSecondNotification() {
    Seed s = seed("500.00");
    VerifyCommand cmd =
        new VerifyCommand(
            PaymentProvider.INSTAPAY_MANUAL,
            "IPN-REPLAY",
            new BigDecimal("500.00"),
            "EGP",
            null,
            s.orderNumber(),
            null,
            null,
            null,
            base);

    txnService.verify(s.orgId(), cmd, s.admin());
    VerifyResult replay = txnService.verify(s.orgId(), cmd, s.admin());

    assertTrue(replay.replay(), "second identical verify is a replay");
    assertEquals(1, orderPaidNotificationIds(s.orgId()).size(), "replay must not re-notify");
  }

  @Test
  void inStoreSale_noOrderPaid_theReceiptIsTheNotification() {
    UUID orgId = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    UUID product = createProduct(orgId, "SKU1");
    createInventory(orgId, product, 10);

    salesOrderService.placeInStoreSale(
        orgId,
        new CustomerInput("Walk In", "walkin@acme.test", null, null),
        List.of(new OrderLineInput(product, 2)),
        List.of(new PaymentInput(PaymentProvider.CASH, null, new BigDecimal("20.00"))),
        null,
        null,
        ActorContext.user(staff.toString()),
        UUID.randomUUID().toString(),
        staff,
        false);

    assertEquals(
        0, orderPaidNotificationIds(orgId).size(), "in-store PAID flip must not fire ORDER_PAID");
  }

  // preferences

  @Test
  void exactPreferenceDisabled_recordedDispatched_zeroDeliveries() {
    Seed s = seed("500.00");
    // Since P5 a customer has two legs — suppress both to pin the fully-suppressed invariant.
    var prefs = new NotificationPreferenceRepositoryFactoryImpl().create(dsl);
    prefs.upsertCustomer(s.orgId(), s.customerId(), "ORDER_PAID", NotificationChannel.EMAIL, false);
    prefs.upsertCustomer(
        s.orgId(), s.customerId(), "ORDER_PAID", NotificationChannel.IN_APP, false);

    verify(s, "500.00", s.orderNumber(), "MATCHED");

    List<UUID> paid = orderPaidNotificationIds(s.orgId());
    assertEquals(1, paid.size(), "a suppressed notification is still recorded");
    assertEquals("DISPATCHED", notificationStatus(paid.get(0)), "finalized with zero deliveries");
    assertEquals(0, deliveryCount(paid.get(0)));
  }

  @Test
  void orgWideUnsubscribe_sameOutcome() {
    Seed s = seed("500.00");
    notificationService.unsubscribeCustomerEmail(s.orgId(), s.customerId());

    verify(s, "500.00", s.orderNumber(), "MATCHED");

    List<UUID> paid = orderPaidNotificationIds(s.orgId());
    assertEquals(1, paid.size());
    // The unsubscribe kills only the email leg — the P5 in_app feed row still lands (and keeps
    // the parent PENDING until the in-app sweep drains it).
    assertEquals(0, emailDeliveryCount(paid.get(0)), "email suppressed");
    assertEquals(1, deliveryCount(paid.get(0)), "the in_app feed leg survives the unsubscribe");
    notificationService.dispatchPendingInApp(100);
    assertEquals("DISPATCHED", notificationStatus(paid.get(0)));
  }

  // helpers

  private record Seed(UUID orgId, UUID admin, UUID customerId, UUID orderId, String orderNumber) {}

  /** Org + admin + customer + a PENDING_PAYMENT order for the customer at the given total. */
  private Seed seed(String grandTotal) {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID customerId = createCustomer(orgId, "nadia@acme.test");
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", orderSeq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.CUSTOMER_ID, customerId)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();
    return new Seed(orgId, admin, customerId, orderId, number);
  }

  private UUID verify(Seed s, String amount, String orderNumber, String expected) {
    VerifyResult r =
        txnService.verify(
            s.orgId(),
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                "IPN-" + refSeq.getAndIncrement(),
                new BigDecimal(amount),
                "EGP",
                null,
                orderNumber,
                null,
                null,
                null,
                base),
            s.admin());
    assertEquals(expected, r.reconciliationStatus().name());
    return r.transaction().getId();
  }

  private List<UUID> orderPaidNotificationIds(UUID orgId) {
    return dsl.select(NOTIFICATION.ID)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ORG_ID.eq(orgId).and(NOTIFICATION.TYPE.eq("ORDER_PAID")))
        .fetch(NOTIFICATION.ID);
  }

  private UUID recipientCustomerId(UUID notificationId) {
    return dsl.select(NOTIFICATION.RECIPIENT_CUSTOMER_ID)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(notificationId))
        .fetchOne(NOTIFICATION.RECIPIENT_CUSTOMER_ID);
  }

  private String notificationBody(UUID notificationId) {
    return dsl.select(NOTIFICATION.BODY)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(notificationId))
        .fetchOne(NOTIFICATION.BODY);
  }

  private String notificationStatus(UUID notificationId) {
    return dsl.select(NOTIFICATION.STATUS)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(notificationId))
        .fetchOne(NOTIFICATION.STATUS, String.class);
  }

  private int deliveryCount(UUID notificationId) {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId)));
  }

  /** Email-channel deliveries only — CUSTOMER recipients also get a P5 in_app feed leg. */
  private int emailDeliveryCount(UUID notificationId) {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.EMAIL.dbValue())));
  }

  private int viewOrderTokenCount(UUID orgId, UUID orderId) {
    return dsl.fetchCount(
        dsl.selectFrom(CUSTOMER_MAGIC_TOKEN)
            .where(
                CUSTOMER_MAGIC_TOKEN
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER_MAGIC_TOKEN.PURPOSE.eq("VIEW_ORDER"))
                    .and(CUSTOMER_MAGIC_TOKEN.RESOURCE_ID.eq(orderId))));
  }

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS, String.class);
  }

  /**
   * An <b>English</b> org. Explicit on purpose: {@code org.default_locale} defaults to {@code 'ar'}
   * (V52), so since slice L a seed that leaves it unset produces Arabic notifications. The
   * assertions below are about notification mechanics, not language, so they pin the locale rather
   * than restating every template in Arabic — the Arabic path has its own coverage.
   */
  private UUID createOrg(String slug) {
    return createOrg(slug, "en");
  }

  private UUID createOrg(String slug, String defaultLocale) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .set(ORG.DEFAULT_LOCALE, defaultLocale)
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

  private UUID createCustomer(UUID org, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, email)
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
