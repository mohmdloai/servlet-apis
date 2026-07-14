package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_EMAIL;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.email.EmailException;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Phase 2 email channel: a CUSTOMER recipient produces an {@code email} delivery + subtype row, the
 * sweeper hands it to an {@link EmailSender}, and success/failure/retry drive the delivery +
 * notification statuses. Uses fake senders (capturing / throwing) — no real SMTP.
 */
@Testcontainers
class CustomerEmailDeliveryIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;

  /** Records every message it is handed; can be armed to throw to exercise retry/fail. */
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
        "TRUNCATE notification_delivery_email, notification_delivery_in_app, notification_delivery,"
            + " notification, customer, org RESTART IDENTITY CASCADE");
  }

  // ───────────────────────────── scenarios ─────────────────────────────

  /** Producing for a CUSTOMER writes a PENDING email delivery + subtype row addressed to them. */
  @Test
  void produce_writesPendingEmailDeliveryAndSubtype() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    NotificationService service = service(new CapturingEmailSender(), 5);

    UUID nid = produceOrderPlacedEmail(service, org, customer, "SO-2026-00001");

    assertEquals("PENDING", notificationStatus(nid));
    UUID deliveryId = emailDeliveryId(nid);
    assertEquals("email", channel(deliveryId));
    assertEquals("PENDING", deliveryStatus(deliveryId));
    assertEquals("nadia@acme.test", toAddress(deliveryId));
  }

  /** Sweep hands the message to the sender, marks delivery SENT and the notification DISPATCHED. */
  @Test
  void sweep_sendsEmailThenMarksSentAndDispatched() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    CapturingEmailSender sender = new CapturingEmailSender();
    NotificationService service = service(sender, 5);
    UUID nid = produceOrderPlacedEmail(service, org, customer, "SO-2026-00007");

    NotificationService.DeliverySummary summary = service.dispatchPendingEmail(100);

    assertEquals(1, summary.sent());
    assertEquals(0, summary.failed());
    UUID deliveryId = emailDeliveryId(nid);
    assertEquals("SENT", deliveryStatus(deliveryId));
    assertNotNull(sentAt(deliveryId));
    assertEquals("DISPATCHED", notificationStatus(nid));

    assertEquals(1, sender.captured.size());
    EmailMessage msg = sender.captured.get(0);
    assertEquals("nadia@acme.test", msg.to());
    assertEquals("New order SO-2026-00007", msg.subject());
    assertTrue(
        msg.html().contains("/api/public/orders/tok-SO-2026-00007"),
        "email body carries the magic link: " + msg.html());
  }

  /** A permanent provider failure with a 1-attempt budget marks the delivery terminally FAILED. */
  @Test
  void sweep_failsTerminallyAfterMaxAttempts() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    CapturingEmailSender sender = new CapturingEmailSender();
    sender.throwOnSend = true;
    NotificationService service = service(sender, 1);
    UUID nid = produceOrderPlacedEmail(service, org, customer, "SO-FAIL");

    NotificationService.DeliverySummary summary = service.dispatchPendingEmail(100);

    assertEquals(0, summary.sent());
    assertEquals(1, summary.failed());
    UUID deliveryId = emailDeliveryId(nid);
    assertEquals("FAILED", deliveryStatus(deliveryId));
    assertEquals(1, attempts(deliveryId));
    assertNotNull(lastError(deliveryId));
    assertEquals("DISPATCHED", notificationStatus(nid), "FAILED is terminal → parent dispatched");
  }

  /** A failure below the attempt budget stays PENDING (retried next sweep), then FAILs. */
  @Test
  void sweep_retriesWhileUnderBudgetThenFails() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    CapturingEmailSender sender = new CapturingEmailSender();
    sender.throwOnSend = true;
    NotificationService service = service(sender, 2);
    UUID nid = produceOrderPlacedEmail(service, org, customer, "SO-RETRY");
    UUID deliveryId = emailDeliveryId(nid);

    service.dispatchPendingEmail(100);
    assertEquals("PENDING", deliveryStatus(deliveryId), "still retryable");
    assertEquals(1, attempts(deliveryId));

    service.dispatchPendingEmail(100);
    assertEquals("FAILED", deliveryStatus(deliveryId), "budget exhausted");
    assertEquals(2, attempts(deliveryId));
  }

  /** If the recipient succeeds and there are no other deliveries, a second sweep is a no-op. */
  @Test
  void sweep_isIdempotentOnceSent() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    CapturingEmailSender sender = new CapturingEmailSender();
    NotificationService service = service(sender, 5);
    produceOrderPlacedEmail(service, org, customer, "SO-ONCE");

    service.dispatchPendingEmail(100);
    NotificationService.DeliverySummary second = service.dispatchPendingEmail(100);

    assertEquals(0, second.picked(), "nothing left pending");
    assertEquals(1, sender.captured.size(), "sent exactly once");
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private static NotificationService service(EmailSender sender, int maxAttempts) {
    return new NotificationService(
        dsl,
        new NotificationRepositoryFactoryImpl(),
        new UserRepositoryFactoryImpl(),
        new CustomerRepositoryFactoryImpl(),
        new com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl(),
        sender,
        new com.loai.inventory.service.MagicLinkService(
            dsl,
            new com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            "http://localhost:8080",
            java.time.Duration.ofDays(30)),
        maxAttempts);
  }

  private UUID produceOrderPlacedEmail(
      NotificationService service, UUID org, UUID customer, String orderNumber) {
    Notification n =
        service.notify(
            dsl,
            org,
            NotificationRecipient.customer(customer),
            NotificationType.ORDER_PLACED,
            Map.of("order_number", orderNumber),
            "sales_order",
            UUID.randomUUID(),
            "http://localhost:8080/api/public/orders/tok-" + orderNumber);
    return n.getId();
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

  private UUID emailDeliveryId(UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.ID)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .and(NOTIFICATION_DELIVERY.CHANNEL.eq("email"))
        .fetchOne(NOTIFICATION_DELIVERY.ID);
  }

  private String notificationStatus(UUID id) {
    return dsl.select(NOTIFICATION.STATUS)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(id))
        .fetchOne(NOTIFICATION.STATUS);
  }

  private String channel(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY.CHANNEL)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY.CHANNEL);
  }

  private String deliveryStatus(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY.STATUS)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY.STATUS);
  }

  private int attempts(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY.ATTEMPTS)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY.ATTEMPTS);
  }

  private Object sentAt(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY.SENT_AT)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY.SENT_AT);
  }

  private Object lastError(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY.LAST_ERROR)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY.LAST_ERROR);
  }

  private String toAddress(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS)
        .from(NOTIFICATION_DELIVERY_EMAIL)
        .where(NOTIFICATION_DELIVERY_EMAIL.DELIVERY_ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS);
  }
}
