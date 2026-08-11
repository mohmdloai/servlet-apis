package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER_MAGIC_TOKEN;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ApprovalRequiredException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.repository.CouponRepositoryFactoryImpl;
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
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.Placed;
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
 * The three events that close the PAID→FULFILLED silence ({@code
 * stories/order_lifecycle_notifications.md}): {@code ORDER_SHIPPED} on the ship txn, {@code
 * ORDER_CANCELLED} on the cancel txn, and {@code PAYMENT_NEEDS_ATTENTION} on the UNDERPAID
 * reconcile branch. Each is produced inside its business transaction, so this pins both halves —
 * that it fires when the business event commits, and that it stays silent when the transaction
 * rolls back or the event is one we deliberately do not narrate.
 *
 * <p>Drives the services directly (no Tomcat); the sweeper is driven manually, per the existing
 * notification ITs.
 */
@Testcontainers
class OrderLifecycleNotificationIT {

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
  static FulfillmentService fulfillmentService;
  static OrderCancellationService cancellationService;

  private final AtomicInteger refSeq = new AtomicInteger(1);
  private final OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

  /** Records every message it is handed. */
  static final class CapturingEmailSender implements EmailSender {
    final List<EmailMessage> captured = new ArrayList<>();

    @Override
    public void send(EmailMessage message) {
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
            notificationService,
            magicLink);
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
            new CouponService(dsl, new CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    cancellationService =
        new OrderCancellationService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new FulfillmentRepositoryFactoryImpl(),
            reservationService,
            refundService,
            notificationService,
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
    emailSender.captured.clear();
    dsl.execute(
        "TRUNCATE notification_preference, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery, notification,"
            + " customer_magic_token, payment_allocation, sales_invoice_line, sales_invoice,"
            + " refund, payment, payment_transaction, fulfillment_line, fulfillment,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, user_org_role, app_user, org, order_number_counter,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // ORDER_SHIPPED

  @Test
  void ship_firesOnce_namesCarrierAndTracking_withFreshMagicLink() {
    Seed s = seedPaidOrder("500.00");

    shipAll(s, "Bosta", "EG-TRACK-99");

    List<UUID> shipped = notificationIds(s.orgId(), "ORDER_SHIPPED");
    assertEquals(1, shipped.size());
    assertEquals(s.customerId(), recipientCustomerId(shipped.get(0)));
    String body = notificationBody(shipped.get(0));
    assertTrue(body.contains(s.orderNumber()), "body names the order: " + body);
    assertTrue(body.contains("Bosta"), "body names the carrier: " + body);
    assertTrue(body.contains("EG-TRACK-99"), "body names the tracking number: " + body);
    // A fresh VIEW_ORDER token was minted in the ship txn — placement, ORDER_PAID and this one.
    assertEquals(3, viewOrderTokenCount(s.orgId(), s.orderId()));

    NotificationService.DeliverySummary summary = notificationService.dispatchPendingEmail(100);

    assertEquals(3, summary.sent(), "placed + paid + shipped emails all drain");
    EmailMessage msg = lastEmail();
    assertEquals("Order " + s.orderNumber() + " has shipped", msg.subject());
    assertTrue(msg.html().contains("Track your order"), "the CTA is the shipped verb");
    assertTrue(msg.html().contains("/orders/"), "email carries the order-view magic link");
  }

  @Test
  void ship_withoutCarrierOrTracking_omitsBothSentences() {
    Seed s = seedPaidOrder("500.00");

    shipAll(s, null, null);

    String body = notificationBody(notificationIds(s.orgId(), "ORDER_SHIPPED").get(0));
    assertTrue(body.contains("is on its way"), "the fact that matters still lands: " + body);
    assertFalse(body.contains("null"), "an absent optional never renders: " + body);
    assertFalse(body.contains("Tracking number"), "no empty tracking sentence: " + body);
    assertFalse(body.contains("handed to"), "no empty carrier sentence: " + body);
  }

  @Test
  void splitOrder_firesOncePerShipment_notOncePerOrder() {
    Seed s = seedPaidOrder("500.00", 2);

    // Two boxes on two days: each line ships as its own fulfillment.
    shipLines(s, List.of(s.lines().get(0)), "Bosta", "BOX-1");
    shipLines(s, List.of(s.lines().get(1)), "Bosta", "BOX-2");

    List<UUID> shipped = notificationIds(s.orgId(), "ORDER_SHIPPED");
    assertEquals(2, shipped.size(), "each real box is its own news");
    assertTrue(notificationBody(shipped.get(0)).contains("BOX-1"));
    assertTrue(notificationBody(shipped.get(1)).contains("BOX-2"));
  }

  @Test
  void doubleShip_isRefused_andDoesNotReNotify() {
    Seed s = seedPaidOrder("500.00");
    UUID fulfillmentId = createFulfillment(s, s.lines(), "Bosta", "EG-TRACK-1");

    fulfillmentService.ship(s.orgId(), fulfillmentId, s.actor());
    // The SHIPPED guard rejects the replay, and the notify rides inside that same refused txn.
    assertThrows(
        ConflictException.class,
        () -> fulfillmentService.ship(s.orgId(), fulfillmentId, s.actor()));

    assertEquals(
        1,
        notificationIds(s.orgId(), "ORDER_SHIPPED").size(),
        "one box on the road is one message, however many times ship is called");
  }

  // ORDER_CANCELLED

  @Test
  void cancelWithNoPrepayment_firesWithoutAnyRefundSentence() {
    Seed s = seedPlacedOrder("500.00", 1);

    cancellationService.cancel(
        s.orgId(), s.orderId(), "out of stock", null, s.staff(), /* callerIsOwnerOrAdmin= */ false);

    List<UUID> cancelled = notificationIds(s.orgId(), "ORDER_CANCELLED");
    assertEquals(1, cancelled.size());
    String body = notificationBody(cancelled.get(0));
    assertTrue(body.contains("has been cancelled"), body);
    assertFalse(
        body.contains("refund"),
        "a shopper who never paid is told nothing about a refund: " + body);
  }

  @Test
  void cancelWithPartialPrepayment_namesTheRefundTotalAsBeingProcessed() {
    Seed s = seedPlacedOrder("500.00", 1);
    verify(s, "400.00", s.orderNumber(), "UNDERPAID"); // a real partial, under the 500 threshold

    cancellationService.cancel(
        s.orgId(), s.orderId(), "customer changed their mind", null, s.staff(), false);

    String body = notificationBody(notificationIds(s.orgId(), "ORDER_CANCELLED").get(0));
    assertTrue(body.contains("400.00"), "body names the refund total: " + body);
    assertTrue(body.contains("EGP"), "body names the currency: " + body);
    // The refund is PENDING — the merchant has not moved money yet, and the copy must not claim so.
    assertTrue(body.contains("is being processed"), body);
    assertFalse(body.contains("has been refunded"), "never claims money already moved: " + body);
  }

  @Test
  void cancelRefusedByTheApprovalGate_staysSilent() {
    Seed s = seedPlacedOrder("900.00", 1);
    verify(s, "600.00", s.orderNumber(), "UNDERPAID"); // above the org's 500 default threshold

    assertThrows(
        ApprovalRequiredException.class,
        () ->
            cancellationService.cancel(s.orgId(), s.orderId(), "too big", null, s.staff(), false));

    assertEquals(
        0,
        notificationIds(s.orgId(), "ORDER_CANCELLED").size(),
        "a rolled-back cancel must not tell the shopper their order was cancelled");
  }

  // PAYMENT_NEEDS_ATTENTION

  @Test
  void underpaid_firesWithTheOutstandingRemainder() {
    Seed s = seedPlacedOrder("1000.00", 1);

    verify(s, "400.00", s.orderNumber(), "UNDERPAID");

    List<UUID> attention = notificationIds(s.orgId(), "PAYMENT_NEEDS_ATTENTION");
    assertEquals(1, attention.size());
    assertEquals(s.customerId(), recipientCustomerId(attention.get(0)));
    String body = notificationBody(attention.get(0));
    assertTrue(body.contains("400.00"), "body acknowledges what arrived: " + body);
    assertTrue(body.contains("600.00"), "body names what is still outstanding: " + body);

    notificationService.dispatchPendingEmail(100);
    EmailMessage msg = lastEmail();
    assertEquals("Order " + s.orderNumber() + " still needs 600.00 EGP", msg.subject());
    assertTrue(msg.html().contains("Complete your payment"), "the CTA is the payment verb");
  }

  @Test
  void secondPartial_firesAgainWithTheSmallerRemainder() {
    Seed s = seedPlacedOrder("1000.00", 1);

    verify(s, "400.00", s.orderNumber(), "UNDERPAID");
    verify(s, "300.00", s.orderNumber(), "UNDERPAID");

    List<UUID> attention = notificationIds(s.orgId(), "PAYMENT_NEEDS_ATTENTION");
    assertEquals(2, attention.size(), "each partial is its own acknowledgement");
    assertTrue(
        notificationBody(attention.get(1)).contains("300.00"),
        "the remainder shrinks as they top up");
  }

  @Test
  void completingTopUp_firesOrderPaid_notAnotherNeedsAttention() {
    Seed s = seedPlacedOrder("1000.00", 1);
    verify(s, "400.00", s.orderNumber(), "UNDERPAID");

    verify(s, "600.00", s.orderNumber(), "MATCHED");

    assertEquals(
        1,
        notificationIds(s.orgId(), "PAYMENT_NEEDS_ATTENTION").size(),
        "the completing payment is good news, not a chase");
    assertEquals(1, notificationIds(s.orgId(), "ORDER_PAID").size());
  }

  @Test
  void overpaid_isPaidNotAttention() {
    Seed s = seedPlacedOrder("500.00", 1);

    verify(s, "600.00", s.orderNumber(), "OVERPAID");

    assertEquals(0, notificationIds(s.orgId(), "PAYMENT_NEEDS_ATTENTION").size());
    assertEquals(1, notificationIds(s.orgId(), "ORDER_PAID").size());
  }

  // preferences — the new types route through the same opt-out resolution

  @Test
  void unsubscribedCustomer_keepsTheFeedRow_losesOnlyTheEmailLeg() {
    Seed s = seedPaidOrder("500.00");
    notificationService.unsubscribeCustomerEmail(s.orgId(), s.customerId());

    shipAll(s, "Bosta", "EG-TRACK-1");

    UUID shipped = notificationIds(s.orgId(), "ORDER_SHIPPED").get(0);
    assertEquals(0, emailDeliveryCount(shipped), "email suppressed");
    assertEquals(1, deliveryCount(shipped), "the in_app feed leg survives");
    notificationService.dispatchPendingInApp(100);
    assertEquals("DISPATCHED", notificationStatus(shipped));
  }

  @Test
  void perTypeOptOutOfBothChannels_recordsTheEventWithZeroDeliveries() {
    Seed s = seedPlacedOrder("1000.00", 1);
    var prefs = new NotificationPreferenceRepositoryFactoryImpl().create(dsl);
    prefs.upsertCustomer(
        s.orgId(), s.customerId(), "PAYMENT_NEEDS_ATTENTION", NotificationChannel.EMAIL, false);
    prefs.upsertCustomer(
        s.orgId(), s.customerId(), "PAYMENT_NEEDS_ATTENTION", NotificationChannel.IN_APP, false);

    verify(s, "400.00", s.orderNumber(), "UNDERPAID");

    List<UUID> attention = notificationIds(s.orgId(), "PAYMENT_NEEDS_ATTENTION");
    assertEquals(1, attention.size(), "a suppressed notification is still recorded");
    assertEquals("DISPATCHED", notificationStatus(attention.get(0)));
    assertEquals(0, deliveryCount(attention.get(0)));
  }

  // helpers

  private record Seed(
      UUID orgId, UUID staff, UUID customerId, UUID orderId, String orderNumber, List<UUID> lines) {
    ActorContext actor() {
      return ActorContext.user(staff.toString());
    }
  }

  /** Org + staff + product/stock + a placed (PENDING_PAYMENT, stock-reserved) ONLINE order. */
  private Seed seedPlacedOrder(String unitTotal, int lineCount) {
    UUID orgId = createOrg("acme");
    UUID staff = createUser("staff@acme.test");
    ActorContext actor = ActorContext.user(staff.toString());

    List<OrderLineInput> lineInputs = new ArrayList<>(lineCount);
    for (int i = 0; i < lineCount; i++) {
      UUID product = createProduct(orgId, "SKU" + i, unitTotal);
      createInventory(orgId, product, 10);
      lineInputs.add(new OrderLineInput(product, 1));
    }

    Placed placed =
        salesOrderService.placeOnlineOrder(
            orgId,
            new CustomerInput("Nadia", "nadia@acme.test", null, null),
            lineInputs,
            UUID.randomUUID().toString(),
            null,
            actor);
    return new Seed(
        orgId,
        staff,
        placed.customer().getId(),
        placed.order().getId(),
        placed.order().getOrderNumber(),
        placed.lines().stream().map(SalesOrderLine::getId).toList());
  }

  /** A placed order carried all the way to PAID by an exact-cover transfer. */
  private Seed seedPaidOrder(String grandTotal) {
    return seedPaidOrder(grandTotal, 1);
  }

  private Seed seedPaidOrder(String unitTotal, int lineCount) {
    Seed s = seedPlacedOrder(unitTotal, lineCount);
    BigDecimal total = new BigDecimal(unitTotal).multiply(BigDecimal.valueOf(lineCount));
    verify(s, total.toPlainString(), s.orderNumber(), "MATCHED");
    return s;
  }

  private void shipAll(Seed s, String carrier, String tracking) {
    shipLines(s, s.lines(), carrier, tracking);
  }

  private void shipLines(Seed s, List<UUID> orderLineIds, String carrier, String tracking) {
    UUID fulfillmentId = createFulfillment(s, orderLineIds, carrier, tracking);
    fulfillmentService.ship(s.orgId(), fulfillmentId, s.actor());
  }

  private UUID createFulfillment(Seed s, List<UUID> orderLineIds, String carrier, String tracking) {
    return fulfillmentService
        .create(
            s.orgId(),
            s.orderId(),
            orderLineIds.stream().map(LineInput::new).toList(),
            carrier,
            tracking,
            null,
            s.actor())
        .fulfillment()
        .getId();
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
            s.staff());
    assertEquals(expected, r.reconciliationStatus().name());
    return r.transaction().getId();
  }

  /** Notification ids of one type, oldest first — the order they were produced in. */
  private List<UUID> notificationIds(UUID orgId, String type) {
    return dsl.select(NOTIFICATION.ID)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ORG_ID.eq(orgId).and(NOTIFICATION.TYPE.eq(type)))
        .orderBy(NOTIFICATION.CREATED_AT.asc(), NOTIFICATION.ID.asc())
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

  private EmailMessage lastEmail() {
    return emailSender.captured.get(emailSender.captured.size() - 1);
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

  private UUID createProduct(UUID org, String sku, String price) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal(price))
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
