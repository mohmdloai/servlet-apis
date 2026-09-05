package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_PUSH;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PUSH_SUBSCRIPTION;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.crypto.P256;
import com.loai.inventory.common.security.VapidKeys;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationPreference;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.PushSubscriptionRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrgRole;
import com.loai.inventory.service.LowStockNotifier;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.PushSubscriptionService;
import com.loai.inventory.service.email.LoggingEmailSender;
import com.loai.inventory.service.push.PushTarget;
import com.loai.inventory.service.push.WebPushConfig;
import com.loai.inventory.service.push.WebPushException;
import com.loai.inventory.service.push.WebPushSender;
import com.loai.inventory.service.whatsapp.LoggingWhatsAppSender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
 * The Web Push channel ({@code stories/web_push_channel.md}, V96), everything up to the push
 * service boundary: the real producer and the real sweeper against a recording sender. One push
 * delivery <b>per device</b>, the payload frozen at produce time, the claim → send → settle walk,
 * the failure taxonomy (410 prunes the device), and the token-generation rule that silences push on
 * every existing sign-out-everywhere path.
 */
@Testcontainers
class WebPushDeliveryIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static RecordingWebPushSender sender;
  static NotificationService notifications;
  static NotificationService shortBudget;
  static PushSubscriptionService subscriptions;
  static UserRepositoryImpl users;
  static final ObjectMapper JSON = new ObjectMapper();

  /** Records every send; can be armed to fail transiently or terminally. */
  static final class RecordingWebPushSender implements WebPushSender {
    final List<PushTarget> targets = new ArrayList<>();
    final List<String> payloads = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public Integer send(PushTarget target, byte[] payload) {
      if (failWith != null) {
        throw failWith;
      }
      targets.add(target);
      payloads.add(new String(payload, StandardCharsets.UTF_8));
      return 201;
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

    sender = new RecordingWebPushSender();
    users = new UserRepositoryImpl(dsl);
    WebPushConfig config = new WebPushConfig(VapidKeys.of(P256.generate()), "mailto:ops@x.test");
    subscriptions =
        new PushSubscriptionService(
            dsl,
            new PushSubscriptionRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl(),
            config);
    notifications = service(NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
    shortBudget = service(2);
  }

  private static NotificationService service(int pushMaxAttempts) {
    return new NotificationService(
        dsl,
        new NotificationRepositoryFactoryImpl(),
        new UserRepositoryFactoryImpl(),
        new CustomerRepositoryFactoryImpl(),
        new NotificationPreferenceRepositoryFactoryImpl(),
        new OrgRepositoryFactoryImpl(),
        new OrgWhatsAppConfigRepositoryFactoryImpl(),
        new LoggingEmailSender(),
        new MagicLinkService(
            dsl,
            new CustomerMagicTokenRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofDays(30)),
        new LoggingWhatsAppSender(),
        NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS,
        new PushSubscriptionRepositoryFactoryImpl(),
        sender,
        pushMaxAttempts);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void fresh() {
    sender.targets.clear();
    sender.payloads.clear();
    sender.failWith = null;
    dsl.execute(
        "TRUNCATE notification_preference, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery_whatsapp,"
            + " notification_delivery_push, notification_delivery, notification,"
            + " customer_magic_token, push_subscription, product, user_org_role, app_user, org"
            + " RESTART IDENTITY CASCADE");
  }

  // Produce: one delivery per device

  @Test
  void aUserWithTwoLiveDevices_getsOneInAppAndTwoPushDeliveries_withTheFrozenPayload() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    String laptop = subscribe(user, "laptop");

    UUID product = UUID.randomUUID();
    UUID notification = notifyUser(org, user, product);

    assertEquals(1, deliveryCount(notification, NotificationChannel.IN_APP));
    assertEquals(2, deliveryCount(notification, NotificationChannel.PUSH));
    List<String> endpoints = pushEndpointsOf(notification);
    assertTrue(endpoints.contains(phone) && endpoints.contains(laptop), endpoints.toString());

    JsonNode payload = json(pushPayloadOf(notification, phone));
    assertEquals(notification.toString(), payload.path("notification_id").asText());
    assertEquals("LOW_STOCK", payload.path("type").asText());
    assertEquals("Low stock: Notebooks", payload.path("title").asText());
    assertTrue(payload.path("body").asText().contains("Notebooks"));
    assertEquals(org.toString(), payload.path("org_id").asText());
    assertEquals("product", payload.path("source_type").asText());
    assertEquals(product.toString(), payload.path("source_id").asText());
    assertFalse(payload.has("link_target"), "the client resolves the route, not the API");
  }

  @Test
  void aUserWithNoDevice_getsInAppOnly_andNothingIsRecordedAsAFailure() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);

    UUID notification = notifyUser(org, user, UUID.randomUUID());

    assertEquals(1, deliveryCount(notification, NotificationChannel.IN_APP));
    assertEquals(0, deliveryCount(notification, NotificationChannel.PUSH));
    assertEquals(0, failedCount(notification));
  }

  @Test
  void aPushOptOut_suppressesTheLegAndLeavesTheFeed_forAllTypesOrOneType() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    subscribe(user, "phone");
    var prefs = new NotificationPreferenceRepositoryFactoryImpl().create(dsl);

    prefs.upsertUser(org, user, NotificationPreference.ALL_TYPES, NotificationChannel.PUSH, false);
    UUID silenced = notifyUser(org, user, UUID.randomUUID());
    assertEquals(0, deliveryCount(silenced, NotificationChannel.PUSH), "ALL/push opt-out");
    assertEquals(1, deliveryCount(silenced, NotificationChannel.IN_APP), "the feed survives");

    prefs.upsertUser(org, user, NotificationPreference.ALL_TYPES, NotificationChannel.PUSH, true);
    prefs.upsertUser(org, user, "LOW_STOCK", NotificationChannel.PUSH, false);
    UUID lowStock = notifyUser(org, user, UUID.randomUUID());
    assertEquals(0, deliveryCount(lowStock, NotificationChannel.PUSH), "per-type opt-out");
    UUID placed = notifyOrderPlaced(org, user);
    assertEquals(1, deliveryCount(placed, NotificationChannel.PUSH), "the other type still goes");
  }

  @Test
  void theStaffFanOut_reachesEachMembersOwnDevices_only() {
    UUID org = createOrg("en");
    UUID alice = createStaff(org);
    UUID bob = createStaff(org);
    String alicePhone = subscribe(alice, "alice-phone");
    subscribe(bob, "bob-phone");
    subscribe(bob, "bob-laptop");

    dsl.transaction(
        cfg ->
            notifications.notifyOrgStaff(
                DSL.using(cfg),
                org,
                NotificationType.ORDER_PLACED,
                Map.of("order_number", "SO-7", "customer_name", "N"),
                "sales_order",
                UUID.randomUUID(),
                "/x"));

    UUID aliceNotification = notificationFor(alice);
    UUID bobNotification = notificationFor(bob);
    assertEquals(1, deliveryCount(aliceNotification, NotificationChannel.PUSH));
    assertEquals(2, deliveryCount(bobNotification, NotificationChannel.PUSH));
    assertEquals(List.of(alicePhone), pushEndpointsOf(aliceNotification));
  }

  // The token-generation rule: every existing sign-out-everywhere path silences push

  @Test
  void aTokenVersionBump_leavesTheRowButSilencesTheDevice_untilItResubscribes() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    assertEquals(
        1, deliveryCount(notifyUser(org, user, UUID.randomUUID()), NotificationChannel.PUSH));

    users.incrementTokenVersion(user); // what logout-all, a password change and a de-privilege do

    assertEquals(1, dsl.fetchCount(PUSH_SUBSCRIPTION), "the row is kept, not deleted");
    assertEquals(
        0, deliveryCount(notifyUser(org, user, UUID.randomUUID()), NotificationChannel.PUSH));

    // The same browser re-posting its subscription (the client re-syncs on load) revives it.
    resubscribe(user, phone);
    assertEquals(
        1, deliveryCount(notifyUser(org, user, UUID.randomUUID()), NotificationChannel.PUSH));
  }

  @Test
  void aDisabledUser_isNotATarget() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    subscribe(user, "phone");

    users.setActive(user, false);

    // notifyOrgStaff already skips inactive members; notify() directly is the sharper test.
    assertEquals(
        0, deliveryCount(notifyUser(org, user, UUID.randomUUID()), NotificationChannel.PUSH));
  }

  // Send: claim → send → settle

  @Test
  void theSweeperSendsTheFrozenPayload_andMarksSentWithTheProviderStatusAndLastUsed() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    UUID notification = notifyUser(org, user, UUID.randomUUID());

    NotificationService.DeliverySummary summary = notifications.dispatchPendingPush(100);

    assertEquals(1, summary.sent());
    assertEquals(1, sender.targets.size());
    assertEquals(phone, sender.targets.get(0).endpoint());
    assertNotNull(sender.targets.get(0).subscriptionId());
    JsonNode payload = json(sender.payloads.get(0));
    assertEquals("Low stock: Notebooks", payload.path("title").asText());
    assertEquals("SENT", pushStatusOf(notification, phone));
    assertEquals(201, providerStatusOf(notification, phone));
    assertNotNull(
        dsl.select(PUSH_SUBSCRIPTION.LAST_USED_AT)
            .from(PUSH_SUBSCRIPTION)
            .where(PUSH_SUBSCRIPTION.ENDPOINT.eq(phone))
            .fetchOne(0, java.time.OffsetDateTime.class),
        "last_used_at is stamped on a successful send");
  }

  @Test
  void a410_failsTheDeliveryAndDeletesTheSubscription_theSiblingDeviceIsUntouched() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    String laptop = subscribe(user, "laptop");
    UUID notification = notifyUser(org, user, UUID.randomUUID());
    // Fail only the phone: arm the sender per target.
    sender.failWith = null;
    WebPushSender selective =
        (target, payload) -> {
          if (target.endpoint().equals(phone)) {
            throw new WebPushException.TerminalWebPushException("push service 410", true, 410);
          }
          return sender.send(target, payload);
        };
    NotificationService withSelective = serviceWith(selective);

    NotificationService.DeliverySummary summary = withSelective.dispatchPendingPush(100);

    assertEquals(1, summary.failed());
    assertEquals(1, summary.sent());
    assertEquals("FAILED", pushStatusOf(notification, phone));
    assertEquals(410, providerStatusOf(notification, phone));
    assertEquals("SENT", pushStatusOf(notification, laptop));
    assertEquals(
        List.of(laptop),
        dsl.select(PUSH_SUBSCRIPTION.ENDPOINT).from(PUSH_SUBSCRIPTION).fetch(0, String.class),
        "the dead device is pruned, the live one stays");
    // The pruned device's subtype row is kept for the audit trail, its FK nulled.
    assertNull(
        dsl.select(NOTIFICATION_DELIVERY_PUSH.SUBSCRIPTION_ID)
            .from(NOTIFICATION_DELIVERY_PUSH)
            .where(NOTIFICATION_DELIVERY_PUSH.ENDPOINT.eq(phone))
            .fetchOne(0, UUID.class));
  }

  @Test
  void aTransientFault_retriesThenFailsAtTheBudget() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    UUID notification = notifyUser(org, user, UUID.randomUUID());
    sender.failWith = new WebPushException("push service 503: try later", 503);

    NotificationService.DeliverySummary first = shortBudget.dispatchPendingPush(100);
    assertEquals(1, first.retried());
    assertEquals(0, first.failed());
    assertEquals("PENDING", pushStatusOf(notification, phone));
    assertEquals(503, providerStatusOf(notification, phone));

    NotificationService.DeliverySummary second = shortBudget.dispatchPendingPush(100);
    assertEquals(1, second.failed());
    assertEquals("FAILED", pushStatusOf(notification, phone));
    assertEquals(1, dsl.fetchCount(PUSH_SUBSCRIPTION), "a 503 is not a verdict on the device");
  }

  @Test
  void aTerminalRejectionThatIsNotGone_failsAtOnceAndKeepsTheSubscription() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    UUID notification = notifyUser(org, user, UUID.randomUUID());
    sender.failWith =
        new WebPushException.TerminalWebPushException("push service 401: bad vapid", false, 401);

    NotificationService.DeliverySummary summary = notifications.dispatchPendingPush(100);

    assertEquals(1, summary.failed());
    assertEquals(0, summary.retried());
    assertEquals("FAILED", pushStatusOf(notification, phone));
    assertEquals(1, dsl.fetchCount(PUSH_SUBSCRIPTION), "our key is wrong, not the device");
  }

  @Test
  void aSubscriptionPrunedBetweenProduceAndSend_failsAtTheClaimWithoutASend() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    UUID notification = notifyUser(org, user, UUID.randomUUID());

    subscriptions.unsubscribe(user, phone); // the user turned push off after the row was written

    NotificationService.DeliverySummary summary = notifications.dispatchPendingPush(100);

    assertEquals(1, summary.failed());
    assertTrue(sender.targets.isEmpty(), "nothing was sent to a device that opted out");
    assertEquals("FAILED", pushStatusOf(notification, phone));
    assertEquals("DISPATCHED", notificationStatus(notification, /* afterInApp= */ true));
  }

  @Test
  void theParentFlipsDispatched_onlyWhenTheInAppAndEveryPushLegAreTerminal() {
    UUID org = createOrg("en");
    UUID user = createStaff(org);
    subscribe(user, "phone");
    subscribe(user, "laptop");
    UUID notification = notifyUser(org, user, UUID.randomUUID());

    notifications.dispatchPendingInApp(100);
    assertEquals("PENDING", notificationStatus(notification, false), "two push legs still open");

    notifications.dispatchPendingPush(100);
    assertEquals("DISPATCHED", notificationStatus(notification, false));
  }

  // LOW_STOCK end to end

  @Test
  void aLowStockCrossing_reachesThePushLeg_withTheProductAsTheSource() {
    UUID org = createOrg("ar");
    UUID user = createStaff(org);
    String phone = subscribe(user, "phone");
    UUID product = createProduct(org, "دفاتر", 5);
    LowStockNotifier notifier =
        new LowStockNotifier(new ProductRepositoryFactoryImpl(), notifications);

    dsl.transaction(
        cfg ->
            notifier.afterSale(
                DSL.using(cfg), org, List.of(new LowStockNotifier.StockMove(product, 6, 5))));

    UUID notification = notificationFor(user);
    assertEquals(1, deliveryCount(notification, NotificationChannel.PUSH));
    JsonNode payload = json(pushPayloadOf(notification, phone));
    assertEquals("LOW_STOCK", payload.path("type").asText());
    assertEquals(product.toString(), payload.path("source_id").asText());
    assertEquals("product", payload.path("source_type").asText());
    assertTrue(payload.path("title").asText().contains("دفاتر"), "rendered in the org's locale");

    assertEquals(1, notifications.dispatchPendingPush(100).sent());
  }

  // helpers

  private static JsonNode json(String raw) {
    try {
      return JSON.readTree(raw);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private NotificationService serviceWith(WebPushSender s) {
    return new NotificationService(
        dsl,
        new NotificationRepositoryFactoryImpl(),
        new UserRepositoryFactoryImpl(),
        new CustomerRepositoryFactoryImpl(),
        new NotificationPreferenceRepositoryFactoryImpl(),
        new OrgRepositoryFactoryImpl(),
        new OrgWhatsAppConfigRepositoryFactoryImpl(),
        new LoggingEmailSender(),
        null,
        new LoggingWhatsAppSender(),
        NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS,
        new PushSubscriptionRepositoryFactoryImpl(),
        s,
        NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
  }

  private UUID notifyUser(UUID org, UUID user, UUID product) {
    return dsl.transactionResult(
            cfg ->
                notifications.notify(
                    DSL.using(cfg),
                    org,
                    NotificationRecipient.user(user),
                    NotificationType.LOW_STOCK,
                    Map.of(
                        "product_id",
                        product.toString(),
                        "name",
                        "Notebooks",
                        "sku",
                        "NB-A5",
                        "available",
                        5,
                        "reorder_point",
                        5),
                    "product",
                    product,
                    "/api/orgs/" + org + "/inventory/" + product))
        .getId();
  }

  private UUID notifyOrderPlaced(UUID org, UUID user) {
    return dsl.transactionResult(
            cfg ->
                notifications.notify(
                    DSL.using(cfg),
                    org,
                    NotificationRecipient.user(user),
                    NotificationType.ORDER_PLACED,
                    Map.of("order_number", "SO-1", "customer_name", "N"),
                    "sales_order",
                    UUID.randomUUID(),
                    "/x"))
        .getId();
  }

  /** A fresh browser subscription with real P-256 material, labelled {@code device}. */
  private String subscribe(UUID user, String device) {
    String endpoint = "https://push.example/send/" + device + "-" + UUID.randomUUID();
    resubscribe(user, endpoint);
    return endpoint;
  }

  private void resubscribe(UUID user, String endpoint) {
    KeyPair browser = P256.generate();
    byte[] auth = new byte[16];
    new java.security.SecureRandom().nextBytes(auth);
    subscriptions.subscribe(
        user,
        endpoint,
        b64(P256.encodePoint((ECPublicKey) browser.getPublic())),
        b64(auth),
        "test device");
  }

  private static String b64(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private int deliveryCount(UUID notificationId, NotificationChannel channel) {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq(channel.dbValue())));
  }

  private int failedCount(UUID notificationId) {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .and(NOTIFICATION_DELIVERY.STATUS.eq("FAILED")));
  }

  private List<String> pushEndpointsOf(UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY_PUSH.ENDPOINT)
        .from(NOTIFICATION_DELIVERY_PUSH)
        .join(NOTIFICATION_DELIVERY)
        .on(NOTIFICATION_DELIVERY.ID.eq(NOTIFICATION_DELIVERY_PUSH.DELIVERY_ID))
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .fetch(0, String.class);
  }

  private String pushPayloadOf(UUID notificationId, String endpoint) {
    return dsl.select(NOTIFICATION_DELIVERY_PUSH.PAYLOAD_JSON)
        .from(NOTIFICATION_DELIVERY_PUSH)
        .join(NOTIFICATION_DELIVERY)
        .on(NOTIFICATION_DELIVERY.ID.eq(NOTIFICATION_DELIVERY_PUSH.DELIVERY_ID))
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .and(NOTIFICATION_DELIVERY_PUSH.ENDPOINT.eq(endpoint))
        .fetchOne(0, org.jooq.JSONB.class)
        .data();
  }

  private String pushStatusOf(UUID notificationId, String endpoint) {
    return dsl.select(NOTIFICATION_DELIVERY.STATUS)
        .from(NOTIFICATION_DELIVERY)
        .join(NOTIFICATION_DELIVERY_PUSH)
        .on(NOTIFICATION_DELIVERY.ID.eq(NOTIFICATION_DELIVERY_PUSH.DELIVERY_ID))
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .and(NOTIFICATION_DELIVERY_PUSH.ENDPOINT.eq(endpoint))
        .fetchOne(0, String.class);
  }

  private Integer providerStatusOf(UUID notificationId, String endpoint) {
    return dsl.select(NOTIFICATION_DELIVERY_PUSH.PROVIDER_STATUS)
        .from(NOTIFICATION_DELIVERY_PUSH)
        .join(NOTIFICATION_DELIVERY)
        .on(NOTIFICATION_DELIVERY.ID.eq(NOTIFICATION_DELIVERY_PUSH.DELIVERY_ID))
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .and(NOTIFICATION_DELIVERY_PUSH.ENDPOINT.eq(endpoint))
        .fetchOne(0, Integer.class);
  }

  private String notificationStatus(UUID notificationId, boolean afterInApp) {
    if (afterInApp) {
      notifications.dispatchPendingInApp(100);
    }
    return dsl.select(NOTIFICATION.STATUS)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(notificationId))
        .fetchOne(0, String.class);
  }

  private UUID notificationFor(UUID user) {
    return dsl.select(NOTIFICATION.ID)
        .from(NOTIFICATION)
        .where(NOTIFICATION.RECIPIENT_USER_ID.eq(user))
        .orderBy(NOTIFICATION.CREATED_AT.desc())
        .limit(1)
        .fetchOne(0, UUID.class);
  }

  private UUID createOrg(String locale) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "acme")
        .set(ORG.SLUG, "acme-" + id)
        .set(ORG.DEFAULT_LOCALE, locale)
        .execute();
    return id;
  }

  private UUID createStaff(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, id)
        .set(USER_ORG_ROLE.ORG_ID, org)
        .set(USER_ORG_ROLE.ROLE, OrgRole.MANAGER)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String name, int reorderPoint) {
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
}
