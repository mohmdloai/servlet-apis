package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PortalNotificationResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationStatus;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.NotificationService.PreferenceInput;
import com.loai.inventory.service.email.LoggingEmailSender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Customer portal notifications (slice P5, {@code stories/portal_notifications.md}) against real
 * Postgres, driving the {@link NotificationService} customer-feed surface the {@code PortalServlet}
 * exposes. Covers the acceptance criteria:
 *
 * <ul>
 *   <li>AC1 — a customer-facing event now produces <b>both</b> an {@code in_app} and an {@code
 *       email} delivery, and the in-app row is readable in the feed immediately (before any sweeper
 *       tick — the DB row is the delivery guarantee).
 *   <li>AC2 — the feed is newest-first and paged; {@code unread=true} narrows; the unread count
 *       matches; a dismissed row disappears.
 *   <li>AC3 — read/dismiss are own-only and idempotent; a foreign or unknown id is the same opaque
 *       404 (never an ownership oracle).
 *   <li>AC4 — the preference matrix: email off keeps the feed, in-app off keeps email, both off
 *       still records the notification, finalized {@code DISPATCHED} with zero deliveries.
 *   <li>AC5 — cross-customer isolation, and the portal projection leaks no internal source id /
 *       magic-link target (JSON scan).
 *   <li>AC6 — the staff feed routing is untouched: a USER recipient still gets exactly one {@code
 *       in_app} delivery (the full staff ITs stay green alongside).
 * </ul>
 */
@Testcontainers
class PortalNotificationsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static NotificationService service;
  static final ObjectMapper mapper = ObjectMapperProvider.build();

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

    MagicLinkService magicLink =
        new MagicLinkService(
            dsl,
            new CustomerMagicTokenRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofDays(30));
    service =
        new NotificationService(
            dsl,
            new NotificationRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new NotificationPreferenceRepositoryFactoryImpl(),
            new LoggingEmailSender(),
            magicLink,
            NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
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
        "TRUNCATE notification_preference, customer_magic_token, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery, notification, customer,"
            + " app_user, org RESTART IDENTITY CASCADE");
  }

  // AC1: dual-channel produce, feed readable immediately

  @Test
  void customerEvent_createsInAppAndEmail_feedReadableBeforeSweeper() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");

    UUID notificationId = notifyCustomer(org, cust, "SO-2026-00001", t(0));

    List<String> channels =
        dsl.select(NOTIFICATION_DELIVERY.CHANNEL)
            .from(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .orderBy(NOTIFICATION_DELIVERY.CHANNEL.asc())
            .fetch(NOTIFICATION_DELIVERY.CHANNEL);
    assertEquals(List.of("email", "in_app"), channels, "both legs are produced");

    // No sweeper ran — the PENDING in-app delivery is already in the feed (the row IS delivery).
    List<InAppFeedItem> feed = service.getCustomerFeed(org, cust, false, 0, 10);
    assertEquals(1, feed.size());
    assertEquals(notificationId, feed.get(0).notification().getId());
    assertNull(feed.get(0).readAt(), "fresh item is unread");
    assertEquals(1, service.countCustomerFeed(org, cust, true), "the badge sees it at once");
  }

  // AC2: newest-first paging, unread narrowing, dismiss hides

  @Test
  void feed_newestFirstPaged_unreadNarrows_dismissedHidden() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    UUID n1 = notifyCustomer(org, cust, "SO-2026-00001", t(0));
    UUID n2 = notifyCustomer(org, cust, "SO-2026-00002", t(1));
    UUID n3 = notifyCustomer(org, cust, "SO-2026-00003", t(2));

    List<InAppFeedItem> page0 = service.getCustomerFeed(org, cust, false, 0, 2);
    assertEquals(
        List.of(n3, n2),
        page0.stream().map(i -> i.notification().getId()).toList(),
        "newest first");
    List<InAppFeedItem> page1 = service.getCustomerFeed(org, cust, false, 1, 2);
    assertEquals(List.of(n1), page1.stream().map(i -> i.notification().getId()).toList());
    assertEquals(3, service.countCustomerFeed(org, cust, false));

    service.markCustomerRead(org, cust, n2);
    assertEquals(
        List.of(n3, n1),
        service.getCustomerFeed(org, cust, true, 0, 10).stream()
            .map(i -> i.notification().getId())
            .toList(),
        "unread=true drops the read row");
    assertEquals(2, service.countCustomerFeed(org, cust, true), "unread count matches");

    service.markCustomerDismissed(org, cust, n3);
    assertEquals(
        List.of(n2, n1),
        service.getCustomerFeed(org, cust, false, 0, 10).stream()
            .map(i -> i.notification().getId())
            .toList(),
        "a dismissed row is hidden even from the full feed");
    assertEquals(1, service.countCustomerFeed(org, cust, true));
  }

  // AC3: read/dismiss own-only, idempotent, opaque 404

  @Test
  void readAndDismiss_idempotent_foreignOrUnknownIsSame404() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");
    UUID owned = notifyCustomer(org, custA, "SO-2026-00001", t(0));

    service.markCustomerRead(org, custA, owned);
    OffsetDateTime firstRead = service.getCustomerFeed(org, custA, false, 0, 10).get(0).readAt();
    service.markCustomerRead(org, custA, owned); // idempotent — no error, timestamp stable
    assertEquals(firstRead, service.getCustomerFeed(org, custA, false, 0, 10).get(0).readAt());

    // Customer B on A's id, and a random id, are the same opaque 404.
    assertThrows(NotFoundException.class, () -> service.markCustomerRead(org, custB, owned));
    assertThrows(NotFoundException.class, () -> service.markCustomerDismissed(org, custB, owned));
    assertThrows(
        NotFoundException.class, () -> service.markCustomerRead(org, custA, UUID.randomUUID()));
    // A staff-feed id is not reachable through the customer mutation either.
    UUID user = createUser("staff@acme.test");
    UUID staffNotification = notifyUser(org, user);
    assertThrows(
        NotFoundException.class, () -> service.markCustomerRead(org, custA, staffNotification));
  }

  // AC4: preference suppression matrix

  @Test
  void preferences_emailOffKeepsFeed_inAppOffKeepsEmail_bothOffDispatchedWithZeroDeliveries() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");

    // email OFF → only the in_app leg is produced; the feed still works.
    service.setCustomerPreferences(
        org, cust, List.of(new PreferenceInput("ALL", NotificationChannel.EMAIL, false)));
    UUID n1 = notifyCustomer(org, cust, "SO-2026-00001", t(0));
    assertEquals(List.of("in_app"), channelsOf(n1));
    assertEquals(1, service.countCustomerFeed(org, cust, true));

    // in_app OFF (email back ON) → only the email leg.
    service.setCustomerPreferences(
        org,
        cust,
        List.of(
            new PreferenceInput("ALL", NotificationChannel.EMAIL, true),
            new PreferenceInput("ALL", NotificationChannel.IN_APP, false)));
    UUID n2 = notifyCustomer(org, cust, "SO-2026-00002", t(1));
    assertEquals(List.of("email"), channelsOf(n2));
    assertEquals(1, service.countCustomerFeed(org, cust, false), "n2 never enters the feed");

    // both OFF → zero deliveries, but the notification is recorded and finalized DISPATCHED.
    service.setCustomerPreferences(
        org, cust, List.of(new PreferenceInput("ALL", NotificationChannel.EMAIL, false)));
    UUID n3 = notifyCustomer(org, cust, "SO-2026-00003", t(2));
    assertTrue(channelsOf(n3).isEmpty());
    assertEquals(
        NotificationStatus.DISPATCHED.name(),
        dsl.select(NOTIFICATION.STATUS)
            .from(NOTIFICATION)
            .where(NOTIFICATION.ID.eq(n3))
            .fetchOne(NOTIFICATION.STATUS));

    // The round-trip read returns what was written (portal GET /notification-preferences).
    assertEquals(2, service.getCustomerPreferences(org, cust).size());
  }

  // AC5: isolation + no-leak projection

  @Test
  void isolation_crossCustomerAndCrossOrg_andNoLeakJson() throws Exception {
    UUID orgA = createOrg();
    UUID orgB = createOrg();
    UUID custA = createCustomer(orgA, "a@acme.test");
    UUID custB = createCustomer(orgA, "b@acme.test");
    notifyCustomer(orgA, custA, "SO-2026-00001", t(0));

    assertEquals(0, service.countCustomerFeed(orgA, custB, false), "B sees nothing of A's");
    assertEquals(
        0,
        service.countCustomerFeed(orgB, custA, false),
        "the same customer id under a foreign org resolves nothing");

    InAppFeedItem item = service.getCustomerFeed(orgA, custA, false, 0, 1).get(0);
    String json = mapper.writeValueAsString(PortalNotificationResponse.from(item, "SO-2026-00001"));
    assertTrue(json.contains("\"order_number\":\"SO-2026-00001\""), "the deep-link handle");
    for (String forbidden :
        List.of("source_id", "source_type", "link_target", "org_id", "customer_id", "recipient")) {
      assertFalse(json.contains(forbidden), "must not leak " + forbidden + " — got " + json);
    }
  }

  // AC6: staff routing untouched

  @Test
  void staffRecipient_stillExactlyOneInAppDelivery() {
    UUID org = createOrg();
    UUID user = createUser("staff@acme.test");
    UUID staffNotification = notifyUser(org, user);
    assertEquals(List.of("in_app"), channelsOf(staffNotification));
    // And the staff feed read still sees it (regression for the shared join).
    assertEquals(1, service.countFeed(org, user, false));
  }

  // helpers

  private static OffsetDateTime t(int hours) {
    return OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC).plusHours(hours);
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, "store-" + id)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Cust")
        .set(CUSTOMER.EMAIL, email)
        .execute();
    return id;
  }

  private UUID createUser(String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    return id;
  }

  /**
   * Produce one ORDER_PAID for the customer inside a txn (as the reconcile path does), then pin
   * {@code created_at} so ordering assertions are deterministic.
   */
  private UUID notifyCustomer(UUID orgId, UUID customerId, String orderNumber, OffsetDateTime at) {
    UUID id =
        dsl.transactionResult(
            cfg ->
                service
                    .notify(
                        DSL.using(cfg),
                        orgId,
                        NotificationRecipient.customer(customerId),
                        NotificationType.ORDER_PAID,
                        Map.of("order_number", orderNumber, "amount", "20.00", "currency", "EGP"),
                        "sales_order",
                        UUID.randomUUID(),
                        "http://localhost:8080/public/orders/tok-" + orderNumber)
                    .getId());
    dsl.update(NOTIFICATION)
        .set(NOTIFICATION.CREATED_AT, at)
        .where(NOTIFICATION.ID.eq(id))
        .execute();
    return id;
  }

  private UUID notifyUser(UUID orgId, UUID userId) {
    return dsl.transactionResult(
        cfg ->
            service
                .notify(
                    DSL.using(cfg),
                    orgId,
                    NotificationRecipient.user(userId),
                    NotificationType.ORDER_PLACED,
                    Map.of("order_number", "SO-2026-00009"),
                    "sales_order",
                    UUID.randomUUID(),
                    "/orgs/" + orgId + "/sales-orders/x")
                .getId());
  }

  private List<String> channelsOf(UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.CHANNEL)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .orderBy(NOTIFICATION_DELIVERY.CHANNEL.asc())
        .fetch(NOTIFICATION_DELIVERY.CHANNEL);
  }
}
