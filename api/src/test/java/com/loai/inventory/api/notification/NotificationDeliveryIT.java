package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_IN_APP;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrgRole;
import com.loai.inventory.service.NotificationService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Phase 1 in-app notification core: produce (transaction-safe) → sweep (deliver) → feed (read /
 * dismiss), plus the staff fan-out that {@code ORDER_PLACED} uses and producer rollback safety.
 */
@Testcontainers
class NotificationDeliveryIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static NotificationService service;

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
    service =
        new NotificationService(
            dsl,
            new NotificationRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl(),
            new com.loai.inventory.service.email.LoggingEmailSender(),
            new com.loai.inventory.service.MagicLinkService(
                dsl,
                new com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl(),
                "http://localhost:8080",
                java.time.Duration.ofDays(30)),
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
        "TRUNCATE notification_delivery_email, notification_delivery_in_app, notification_delivery,"
            + " notification, user_org_role, app_user, org RESTART IDENTITY CASCADE");
  }

  // ───────────────────────────── scenarios ─────────────────────────────

  /** Produce → the event + one PENDING in-app delivery + subtype row all exist. */
  @Test
  void produce_writesPendingNotificationAndInAppDelivery() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test", true);

    UUID notificationId = produceForUser(org, user);

    assertEquals("PENDING", notificationStatus(notificationId));
    assertEquals(1, deliveryCount(notificationId));
    assertEquals("PENDING", deliveryStatus(notificationId));
    assertTrue(inAppRowExists(notificationId), "in-app subtype row created in same txn");
  }

  /** Sweep an in-app delivery → SENT, and its now-terminal parent flips to DISPATCHED. */
  @Test
  void sweep_marksDeliverySentAndNotificationDispatched() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test", true);
    UUID notificationId = produceForUser(org, user);

    NotificationService.DeliverySummary summary = service.dispatchPendingInApp(100);

    assertEquals(1, summary.picked());
    assertEquals(1, summary.sent());
    assertEquals(0, summary.failed());
    assertEquals("SENT", deliveryStatus(notificationId));
    assertNotNull(deliverySentAt(notificationId));
    assertEquals("DISPATCHED", notificationStatus(notificationId));
    assertNotNull(notificationDispatchedAt(notificationId));

    // Idempotent: a second sweep finds nothing pending.
    assertEquals(0, service.dispatchPendingInApp(100).picked());
  }

  /** Feed lists own items; read flips unread→read; dismiss hides it. */
  @Test
  void feed_listReadDismiss() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test", true);
    UUID notificationId = produceForUser(org, user);

    List<InAppFeedItem> all = service.getFeed(org, user, false, 0, 10);
    assertEquals(1, all.size());
    assertEquals(notificationId, all.get(0).notification().getId());
    assertNull(all.get(0).readAt());
    assertEquals(1, service.getFeed(org, user, true, 0, 10).size(), "unread filter shows it");

    service.markRead(org, user, notificationId);
    assertNotNull(service.getFeed(org, user, false, 0, 10).get(0).readAt());
    assertEquals(0, service.getFeed(org, user, true, 0, 10).size(), "read item leaves unread feed");

    service.markDismissed(org, user, notificationId);
    assertEquals(0, service.getFeed(org, user, false, 0, 10).size(), "dismissed item leaves feed");
  }

  /** Feed mutations are own-only: a wrong id or another user's attempt is a 404. */
  @Test
  void feedMutation_ownOnly_404otherwise() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test", true);
    UUID other = createUser("other@acme.test", true);
    UUID notificationId = produceForUser(org, user);

    assertThrows(NotFoundException.class, () -> service.markRead(org, user, UUID.randomUUID()));
    assertThrows(NotFoundException.class, () -> service.markRead(org, other, notificationId));
    // The rightful owner still succeeds.
    service.markRead(org, user, notificationId);
  }

  /** notifyOrgStaff fans out to STAFF/MANAGER/OWNER only — not VIEWER, inactive, or another org. */
  @Test
  void fanOut_targetsActiveStaffOnly() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("globex");
    UUID staff = createUser("staff@acme.test", true);
    UUID manager = createUser("manager@acme.test", true);
    UUID viewer = createUser("viewer@acme.test", true);
    UUID inactiveStaff = createUser("ghost@acme.test", false);
    UUID otherOrgStaff = createUser("b@globex.test", true);
    grantRole(staff, orgA, OrgRole.STAFF);
    grantRole(manager, orgA, OrgRole.MANAGER);
    grantRole(viewer, orgA, OrgRole.VIEWER);
    grantRole(inactiveStaff, orgA, OrgRole.STAFF);
    grantRole(otherOrgStaff, orgB, OrgRole.STAFF);

    dsl.transaction(
        cfg ->
            service.notifyOrgStaff(
                DSL.using(cfg),
                orgA,
                NotificationType.ORDER_PLACED,
                Map.of("order_number", "SO-2026-00001"),
                "sales_order",
                UUID.randomUUID(),
                "/orgs/" + orgA + "/sales-orders/x"));

    assertEquals(2, notificationCountForOrg(orgA), "only STAFF + MANAGER, active, this org");
    assertEquals(Set.of(staff, manager), recipientUserIdsForOrg(orgA));
    assertEquals(0, notificationCountForOrg(orgB));
  }

  /** Producer runs in the caller's txn: if that txn rolls back, no notification survives. */
  @Test
  void rollbackSafety_noRowsWhenTxnThrows() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test", true);

    assertThrows(
        RuntimeException.class,
        () ->
            dsl.transaction(
                cfg -> {
                  service.notify(
                      DSL.using(cfg),
                      org,
                      NotificationRecipient.user(user),
                      NotificationType.ORDER_PLACED,
                      Map.of("order_number", "SO-2026-00001"),
                      "sales_order",
                      UUID.randomUUID(),
                      "/link");
                  throw new IllegalStateException("boom — force rollback");
                }));

    assertEquals(0, notificationCountForOrg(org));
    assertEquals(0, dsl.fetchCount(NOTIFICATION_DELIVERY));
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private UUID produceForUser(UUID org, UUID user) {
    return dsl.transactionResult(
        cfg ->
            service
                .notify(
                    DSL.using(cfg),
                    org,
                    NotificationRecipient.user(user),
                    NotificationType.ORDER_PLACED,
                    Map.of("order_number", "SO-2026-00001"),
                    "sales_order",
                    UUID.randomUUID(),
                    "/orgs/" + org + "/sales-orders/x")
                .getId());
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

  private UUID createUser(String email, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-" + email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .set(APP_USER.ACTIVE, active)
        .execute();
    return id;
  }

  private void grantRole(UUID user, UUID org, OrgRole role) {
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, user)
        .set(USER_ORG_ROLE.ORG_ID, org)
        .set(USER_ORG_ROLE.ROLE, role)
        .execute();
  }

  private String notificationStatus(UUID id) {
    return dsl.select(NOTIFICATION.STATUS)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(id))
        .fetchOne(NOTIFICATION.STATUS);
  }

  private java.time.OffsetDateTime notificationDispatchedAt(UUID id) {
    return dsl.select(NOTIFICATION.DISPATCHED_AT)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(id))
        .fetchOne(NOTIFICATION.DISPATCHED_AT);
  }

  private int deliveryCount(UUID notificationId) {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId)));
  }

  private String deliveryStatus(UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.STATUS)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .fetchAny(NOTIFICATION_DELIVERY.STATUS);
  }

  private java.time.OffsetDateTime deliverySentAt(UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.SENT_AT)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .fetchAny(NOTIFICATION_DELIVERY.SENT_AT);
  }

  private boolean inAppRowExists(UUID notificationId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(NOTIFICATION_DELIVERY_IN_APP)
            .join(NOTIFICATION_DELIVERY)
            .on(NOTIFICATION_DELIVERY.ID.eq(NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID))
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId)));
  }

  private int notificationCountForOrg(UUID org) {
    return dsl.fetchCount(dsl.selectFrom(NOTIFICATION).where(NOTIFICATION.ORG_ID.eq(org)));
  }

  private Set<UUID> recipientUserIdsForOrg(UUID org) {
    return dsl.select(NOTIFICATION.RECIPIENT_USER_ID)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ORG_ID.eq(org))
        .fetchSet(NOTIFICATION.RECIPIENT_USER_ID);
  }
}
