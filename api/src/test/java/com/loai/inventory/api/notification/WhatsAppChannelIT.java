package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_WHATSAPP;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.OrgWhatsAppService;
import com.loai.inventory.service.email.LoggingEmailSender;
import com.loai.inventory.service.whatsapp.WhatsAppException;
import com.loai.inventory.service.whatsapp.WhatsAppMessage;
import com.loai.inventory.service.whatsapp.WhatsAppSender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Slice B — the WhatsApp channel ({@code stories/whatsapp_channel.md}), everything up to the Meta
 * boundary. Drives the real producer and the real sweeper against a capturing sender, so the whole
 * path is exercised without a WhatsApp Business Account: channel resolution, the frozen template
 * invocation, claim → send → settle, and the failure taxonomy.
 */
@Testcontainers
class WhatsAppChannelIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CapturingWhatsAppSender sender;
  static NotificationService notifications;
  static OrgWhatsAppService whatsAppService;
  static SecretBox secretBox;

  /** Records every send; can be armed to fail transiently or terminally. */
  static final class CapturingWhatsAppSender implements WhatsAppSender {
    final List<WhatsAppMessage> captured = new ArrayList<>();
    final List<OrgWhatsAppConfig> configs = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public String send(OrgWhatsAppConfig config, WhatsAppMessage message) {
      if (failWith != null) {
        throw failWith;
      }
      configs.add(config);
      captured.add(message);
      return "wamid.TEST" + captured.size();
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

    secretBox = SecretBox.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
    sender = new CapturingWhatsAppSender();
    whatsAppService =
        new OrgWhatsAppService(dsl, new OrgWhatsAppConfigRepositoryFactoryImpl(), secretBox);
    notifications =
        new NotificationService(
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
            sender,
            NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void fresh() {
    sender.captured.clear();
    sender.configs.clear();
    sender.failWith = null;
    dsl.execute(
        "TRUNCATE notification_preference, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery_whatsapp,"
            + " notification_delivery, notification, customer_magic_token, org_whatsapp_config,"
            + " customer, user_org_role, app_user, org RESTART IDENTITY CASCADE");
  }

  // The three conditions that make the channel exist at all

  @Test
  void aConnectedOrgAndAReachableCustomer_getsAWhatsAppLeg_andItSends() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);

    UUID notification = notifyShipped(org, customer);

    assertEquals(1, whatsAppDeliveryCount(notification), "the third leg was written");
    // The invocation is frozen at produce time — a template name and language, not prose.
    assertEquals("order_shipped", templateNameOf(notification));
    assertEquals("en", templateLanguageOf(notification));
    assertEquals("+201012345678", toNumberOf(notification));

    NotificationService.DeliverySummary summary = notifications.dispatchPendingWhatsApp(100);

    assertEquals(1, summary.sent());
    assertEquals(1, sender.captured.size());
    assertEquals(List.of("SO-1"), sender.captured.get(0).bodyParams());
    // The sending identity is the MERCHANT's, resolved per message — that is the whole point of a
    // per-merchant WABA rather than a platform sender.
    assertEquals(org, sender.configs.get(0).orgId());
    assertEquals("PHONE-1", sender.configs.get(0).phoneNumberId());
    assertNotNull(providerMessageIdOf(notification), "the wamid is kept for a future webhook");
  }

  @Test
  void anUnconnectedOrg_hasNoWhatsAppLegAtAll() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");

    UUID notification = notifyShipped(org, customer);

    assertEquals(0, whatsAppDeliveryCount(notification), "no config, no channel");
    assertTrue(deliveryCount(notification) >= 1, "the other legs are unaffected");
  }

  @Test
  void aCustomerWithNoDialableNumber_hasNoWhatsAppLeg() {
    UUID org = createOrg("en");
    // phone typed but unparseable, so V79 left phone_e164 null — unreachable, not an error.
    UUID customer = createCustomer(org, null);
    connect(org);

    UUID notification = notifyShipped(org, customer);

    assertEquals(0, whatsAppDeliveryCount(notification));
  }

  @Test
  void aDisabledConfig_stopsSendingWithoutLosingTheCredentials() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);
    whatsAppService.setEnabled(org, false);

    assertEquals(0, whatsAppDeliveryCount(notifyShipped(org, customer)));
    assertTrue(whatsAppService.status(org).connected(), "the credentials are still there");

    whatsAppService.setEnabled(org, true);
    assertEquals(1, whatsAppDeliveryCount(notifyShipped(org, customer)), "resumed");
  }

  @Test
  void aTypeWithNoApprovedTemplate_getsNoWhatsAppLeg() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);

    // REVIEW_REQUESTED is deliberately absent from WhatsAppTemplates — Meta would class it as
    // marketing, and this epic does not send paid marketing.
    UUID notification =
        dsl.transactionResult(
                cfg ->
                    notifications.notify(
                        DSL.using(cfg),
                        org,
                        NotificationRecipient.customer(customer),
                        NotificationType.REVIEW_REQUESTED,
                        Map.of("order_number", "SO-9"),
                        "sales_order",
                        UUID.randomUUID(),
                        "http://x/y"))
            .getId();

    assertEquals(0, whatsAppDeliveryCount(notification));
  }

  // Locale and preferences ride the existing machinery

  @Test
  void theTemplateLanguageFollowsTheResolvedLocale() {
    UUID org = createOrg("ar");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);

    assertEquals("ar", templateLanguageOf(notifyShipped(org, customer)));
  }

  @Test
  void aPerChannelOptOut_suppressesWhatsAppAndLeavesTheOthers() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);
    new NotificationPreferenceRepositoryFactoryImpl()
        .create(dsl)
        .upsertCustomer(org, customer, "ORDER_SHIPPED", NotificationChannel.WHATSAPP, false);

    UUID notification = notifyShipped(org, customer);

    assertEquals(0, whatsAppDeliveryCount(notification), "opted out of WhatsApp only");
    assertTrue(deliveryCount(notification) >= 1, "the feed and email legs survive");
  }

  // The failure taxonomy

  @Test
  void aTransientProviderFault_retries() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);
    notifyShipped(org, customer);
    sender.failWith = new WhatsAppException("WhatsApp API 503: try later");

    NotificationService.DeliverySummary summary = notifications.dispatchPendingWhatsApp(100);

    assertEquals(1, summary.retried());
    assertEquals(0, summary.failed());
  }

  @Test
  void aTerminalRejection_failsAtOnceWithoutBurningTheRetryBudget() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);
    UUID notification = notifyShipped(org, customer);
    sender.failWith =
        new com.loai.inventory.service.whatsapp.CloudApiWhatsAppSender.TerminalWhatsAppException(
            "WhatsApp API 400: template not approved");

    NotificationService.DeliverySummary summary = notifications.dispatchPendingWhatsApp(100);

    // An unapproved template will never approve itself on the second attempt; retrying four more
    // times only delays the FAILED that tells someone to fix it.
    assertEquals(1, summary.failed());
    assertEquals(0, summary.retried());
    assertEquals("FAILED", whatsAppDeliveryStatus(notification));
  }

  @Test
  void disconnectingBetweenProduceAndSend_failsTheDeliveryRatherThanRetryingForever() {
    UUID org = createOrg("en");
    UUID customer = createCustomer(org, "+201012345678");
    connect(org);
    UUID notification = notifyShipped(org, customer);

    whatsAppService.disconnect(org); // the merchant left after the row was written

    NotificationService.DeliverySummary summary = notifications.dispatchPendingWhatsApp(100);

    assertEquals(1, summary.failed());
    assertEquals("FAILED", whatsAppDeliveryStatus(notification));
    assertTrue(sender.captured.isEmpty(), "nothing was sent as a merchant who disconnected");
  }

  // The credential

  @Test
  void theAccessTokenIsSealedAtRest_andNoReadReturnsIt() {
    UUID org = createOrg("en");
    connect(org);

    String stored =
        dsl.select(
                com.loai.inventory.repository.generated.Tables.ORG_WHATSAPP_CONFIG
                    .ACCESS_TOKEN_ENCRYPTED)
            .from(com.loai.inventory.repository.generated.Tables.ORG_WHATSAPP_CONFIG)
            .where(
                com.loai.inventory.repository.generated.Tables.ORG_WHATSAPP_CONFIG.ORG_ID.eq(org))
            .fetchOne(0, String.class);

    assertFalse(stored.contains("SECRET-TOKEN"), "the raw token is not in the database");
    assertEquals("SECRET-TOKEN", secretBox.decrypt(stored), "…but it round-trips for the sender");

    // The status read is the only read a merchant gets, and it carries no credential field.
    OrgWhatsAppService.ConnectionStatus status = whatsAppService.status(org);
    assertTrue(status.connected());
    assertEquals("PHONE-1", status.phoneNumberId());
  }

  @Test
  void withNoEncryptionKey_connectingIsRefusedRatherThanStoringInTheClear() {
    UUID org = createOrg("en");
    OrgWhatsAppService noKey =
        new OrgWhatsAppService(
            dsl, new OrgWhatsAppConfigRepositoryFactoryImpl(), SecretBox.fromBase64Key(null));

    assertThrows(
        ConflictException.class,
        () -> noKey.connect(org, "WABA-1", "PHONE-1", "+201000000000", "SECRET-TOKEN"));
    assertFalse(whatsAppService.status(org).connected(), "nothing was stored");
  }

  // helpers

  private void connect(UUID org) {
    whatsAppService.connect(org, "WABA-1", "PHONE-1", "01000000000", "SECRET-TOKEN");
  }

  private UUID notifyShipped(UUID org, UUID customer) {
    return dsl.transactionResult(
            cfg ->
                notifications.notify(
                    DSL.using(cfg),
                    org,
                    NotificationRecipient.customer(customer),
                    NotificationType.ORDER_SHIPPED,
                    Map.of("order_number", "SO-1"),
                    "sales_order",
                    UUID.randomUUID(),
                    "http://localhost:8080/en/acme/orders/tok"))
        .getId();
  }

  private int whatsAppDeliveryCount(UUID notificationId) {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.WHATSAPP.dbValue())));
  }

  private int deliveryCount(UUID notificationId) {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId)));
  }

  private String whatsAppDeliveryStatus(UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.STATUS)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .and(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.WHATSAPP.dbValue()))
        .fetchOne(0, String.class);
  }

  private String templateNameOf(UUID notificationId) {
    return whatsAppField(notificationId, NOTIFICATION_DELIVERY_WHATSAPP.TEMPLATE_NAME);
  }

  private String templateLanguageOf(UUID notificationId) {
    return whatsAppField(notificationId, NOTIFICATION_DELIVERY_WHATSAPP.TEMPLATE_LANGUAGE);
  }

  private String toNumberOf(UUID notificationId) {
    return whatsAppField(notificationId, NOTIFICATION_DELIVERY_WHATSAPP.TO_NUMBER);
  }

  private String providerMessageIdOf(UUID notificationId) {
    return whatsAppField(notificationId, NOTIFICATION_DELIVERY_WHATSAPP.PROVIDER_MESSAGE_ID);
  }

  private String whatsAppField(UUID notificationId, org.jooq.Field<String> field) {
    return dsl.select(field)
        .from(NOTIFICATION_DELIVERY_WHATSAPP)
        .join(NOTIFICATION_DELIVERY)
        .on(NOTIFICATION_DELIVERY.ID.eq(NOTIFICATION_DELIVERY_WHATSAPP.DELIVERY_ID))
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .fetchOne(0, String.class);
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

  private UUID createCustomer(UUID org, String phoneE164) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, id + "@acme.test")
        .set(CUSTOMER.PHONE, phoneE164 == null ? "call the shop" : phoneE164)
        .set(CUSTOMER.PHONE_E164, phoneE164)
        .execute();
    return id;
  }

  @SuppressWarnings("unused")
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

  @SuppressWarnings("unused")
  private long notificationCount(UUID org) {
    return dsl.fetchCount(dsl.selectFrom(NOTIFICATION).where(NOTIFICATION.ORG_ID.eq(org)));
  }
}
