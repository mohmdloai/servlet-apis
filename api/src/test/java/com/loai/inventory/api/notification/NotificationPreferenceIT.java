package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_PREFERENCE;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ValidationException;
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
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.MagicLinkService.ResolvedUnsubscribe;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * Phase 3 preferences (opt-out): a preference row suppresses a {@code (subject, type, channel)}
 * delivery; absence means enabled; an exact-type row beats an {@code ALL} wildcard. Plus the staff
 * CRUD round-trip and the customer unsubscribe flow (token → apply → email suppressed).
 *
 * <p>Also pins the invariants a single-threaded happy path can't: the opt-out upsert is atomic
 * under concurrency, magic tokens are bound to their purpose, and resolution is isolated by
 * subject-type, org, and channel.
 */
@Testcontainers
class NotificationPreferenceIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static NotificationService service;
  static MagicLinkService magicLink;

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
    magicLink =
        new MagicLinkService(
            dsl,
            new CustomerMagicTokenRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofDays(30));
    service =
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

  // ───────────────────────────── scenarios ─────────────────────────────

  /** No preference row → the notification fires (opt-out default). */
  @Test
  void noPreference_firesByDefault() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test");

    UUID nid = notifyUser(org, user);

    assertEquals("PENDING", notificationStatus(nid));
    assertEquals(1, deliveryCount(nid));
  }

  /**
   * A USER opt-out suppresses that channel; the notification is recorded but DISPATCHED (0 rows).
   */
  @Test
  void userOptOut_suppressesInAppDelivery() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test");
    service.setUserPreferences(
        org, user, List.of(new PreferenceInput("ORDER_PLACED", NotificationChannel.IN_APP, false)));

    UUID nid = notifyUser(org, user);

    assertEquals(0, deliveryCount(nid), "opted-out channel produced no delivery");
    assertEquals("DISPATCHED", notificationStatus(nid), "nothing to deliver → finalized");
  }

  /** One user's opt-out does not affect another user. */
  @Test
  void optOut_isPerSubject() {
    UUID org = createOrg("acme");
    UUID optedOut = createUser("a@acme.test");
    UUID normal = createUser("b@acme.test");
    service.setUserPreferences(
        org,
        optedOut,
        List.of(new PreferenceInput("ORDER_PLACED", NotificationChannel.IN_APP, false)));

    assertEquals(0, deliveryCount(notifyUser(org, optedOut)));
    assertEquals(1, deliveryCount(notifyUser(org, normal)));
  }

  /** An {@code ALL} wildcard suppresses every type; an exact-type row overrides it. */
  @Test
  void exactTypeBeatsAllWildcard() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test");
    // ALL=off would suppress, but the exact ORDER_PLACED=on wins.
    service.setUserPreferences(
        org,
        user,
        List.of(
            new PreferenceInput(
                NotificationPreference.ALL_TYPES, NotificationChannel.IN_APP, false),
            new PreferenceInput("ORDER_PLACED", NotificationChannel.IN_APP, true)));

    assertEquals(1, deliveryCount(notifyUser(org, user)), "exact enabled row overrides ALL=off");
  }

  /** ALL=off alone suppresses ORDER_PLACED (no exact row to override it). */
  @Test
  void allWildcardOff_suppresses() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test");
    service.setUserPreferences(
        org,
        user,
        List.of(
            new PreferenceInput(
                NotificationPreference.ALL_TYPES, NotificationChannel.IN_APP, false)));

    assertEquals(0, deliveryCount(notifyUser(org, user)));
  }

  /** Staff CRUD round-trips; PUT merges (upsert), and an unknown type is rejected. */
  @Test
  void staffPreferences_roundTripAndValidate() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test");

    List<NotificationPreference> set =
        service.setUserPreferences(
            org,
            user,
            List.of(new PreferenceInput("ORDER_PLACED", NotificationChannel.IN_APP, false)));
    assertEquals(1, set.size());
    assertEquals("ORDER_PLACED", set.get(0).type());
    assertFalse(set.get(0).enabled());

    // Re-PUT the same key flips it (merge, not duplicate).
    service.setUserPreferences(
        org, user, List.of(new PreferenceInput("ORDER_PLACED", NotificationChannel.IN_APP, true)));
    List<NotificationPreference> after = service.getUserPreferences(org, user);
    assertEquals(1, after.size(), "upsert merged, not duplicated");
    assertTrue(after.get(0).enabled());

    assertThrows(
        ValidationException.class,
        () ->
            service.setUserPreferences(
                org,
                user,
                List.of(new PreferenceInput("NOT_A_TYPE", NotificationChannel.IN_APP, false))));
  }

  /** Unsubscribe: mint token → resolve → apply → the customer's next email is suppressed. */
  @Test
  void customerUnsubscribe_suppressesEmail() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    // Before unsubscribe: a customer notification produces one email delivery (plus the P5
    // in_app feed leg — count the email channel specifically).
    assertEquals(1, emailDeliveryCount(notifyCustomer(org, customer)));

    String url = magicLink.issueUnsubscribeLink(dsl, org, customer, now);
    String token = url.substring(url.lastIndexOf('/') + 1);
    Optional<ResolvedUnsubscribe> resolved = magicLink.resolveUnsubscribe(token, now);
    assertTrue(resolved.isPresent());
    assertEquals(org, resolved.get().orgId());
    assertEquals(customer, resolved.get().customerId());

    service.unsubscribeCustomerEmail(resolved.get().orgId(), resolved.get().customerId());

    UUID nid = notifyCustomer(org, customer);
    assertEquals(0, emailDeliveryCount(nid), "unsubscribed → no email delivery");
    // The P5 in_app feed leg survives an EMAIL unsubscribe (the whole point of the dual channel),
    // so the parent finalizes only after the in-app sweep drains it.
    assertEquals(1, deliveryCount(nid), "the in_app feed leg is untouched");
    service.dispatchPendingInApp(100);
    assertEquals("DISPATCHED", notificationStatus(nid));
  }

  @Test
  void unsubscribeLink_targetsTheStorefrontPage_notTheApi() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    String slug = dsl.select(ORG.SLUG).from(ORG).where(ORG.ID.eq(org)).fetchOne(ORG.SLUG);

    String url =
        magicLink.issueUnsubscribeLink(dsl, org, customer, OffsetDateTime.now(ZoneOffset.UTC));

    // The emailed address is a page a human lands on, not the endpoint that applies the change:
    // PublicUnsubscribeServlet acts on GET, so a link-prefetching mail client following the old
    // API URL unsubscribed people who never clicked.
    assertFalse(url.contains("/api/public/unsubscribe/"), "the footer must not point at the API");
    assertTrue(
        url.startsWith("http://localhost:8080/ar/" + slug + "/unsubscribe/"),
        "locale + org slug + /unsubscribe/{token}, the issueOrderViewLink shape — was: " + url);
  }

  @Test
  void resolveUnsubscribe_unknownToken_isEmpty() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    assertTrue(magicLink.resolveUnsubscribe("nope", now).isEmpty());
  }

  // ─────────────────────── concurrency, purpose & isolation ───────────────────────

  /**
   * The opt-out upsert is a single atomic {@code INSERT ... ON CONFLICT}, so concurrent first-time
   * writes for the same {@code (org, subject, type, channel)} all succeed and converge on one
   * disabled row — no unique-constraint collision. This is the production case where an email
   * client prefetches the one-click unsubscribe URL while the customer also clicks it: both
   * requests resolve the same token and call {@code unsubscribeCustomerEmail} at once.
   */
  @Test
  void concurrentUnsubscribe_isAtomicAndIdempotent() throws Exception {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");

    int threads = 12;
    CyclicBarrier startLine = new CyclicBarrier(threads);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger threw = new AtomicInteger();
    List<String> errors = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              startLine.await(); // release all at once → maximise the insert overlap
              service.unsubscribeCustomerEmail(org, customer);
              ok.incrementAndGet();
            } catch (Exception e) {
              threw.incrementAndGet();
              errors.add(e.getClass().getSimpleName() + ": " + rootMessage(e));
            } finally {
              done.countDown();
            }
          });
    }
    done.await();
    pool.shutdownNow();

    long rows =
        dsl.selectCount()
            .from(NOTIFICATION_PREFERENCE)
            .where(NOTIFICATION_PREFERENCE.CUSTOMER_ID.eq(customer))
            .fetchOne(0, long.class);

    // Idempotent: whatever the concurrency, exactly one disabled row.
    assertEquals(1, rows, "exactly one preference row after the storm");
    assertFalse(enabledFlag(customer), "final state is 'email off'");
    // Atomic: every concurrent caller succeeds — no loser hits a unique-constraint collision.
    assertEquals(0, threw.get(), "no collisions under concurrency; errors=" + errors);
    assertEquals(threads, ok.get(), "all callers succeeded");
  }

  /** A VIEW_ORDER token must not resolve on the unsubscribe route, and vice-versa. */
  @Test
  void magicTokens_areBoundToTheirPurpose() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    String viewToken =
        lastSegment(
            magicLink.issueOrderViewLink(dsl, org, customer, UUID.randomUUID(), now).absolute());
    String unsubToken = lastSegment(magicLink.issueUnsubscribeLink(dsl, org, customer, now));

    assertTrue(magicLink.resolveOrderView(viewToken, now).isPresent());
    assertTrue(magicLink.resolveUnsubscribe(unsubToken, now).isPresent());
    assertTrue(
        magicLink.resolveUnsubscribe(viewToken, now).isEmpty(),
        "VIEW_ORDER token cannot unsubscribe");
    assertTrue(
        magicLink.resolveOrderView(unsubToken, now).isEmpty(),
        "UNSUBSCRIBE token cannot view an order");
  }

  /**
   * A USER email opt-out sharing the very same UUID as a customer must NOT suppress that customer's
   * email — resolution is keyed on {@code subject_type}, not just the id.
   */
  @Test
  void optOut_isBoundToSubjectType_evenWhenIdsCollide() {
    UUID org = createOrg("acme");
    UUID shared = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, shared)
        .set(APP_USER.EMAIL, shared + "@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .set(APP_USER.ACTIVE, true)
        .execute();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, shared)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, "nadia@acme.test")
        .execute();

    service.setUserPreferences(
        org,
        shared,
        List.of(new PreferenceInput("ORDER_PLACED", NotificationChannel.EMAIL, false)));

    assertEquals(
        1,
        emailDeliveryCount(notifyCustomer(org, shared)),
        "USER opt-out must not suppress the CUSTOMER's email");
  }

  /** An opt-out in one org does not suppress the same user in another org. */
  @Test
  void optOut_isPerOrg() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("globex");
    UUID user = createUser("staff@shared.test");
    service.setUserPreferences(
        orgA,
        user,
        List.of(new PreferenceInput("ORDER_PLACED", NotificationChannel.IN_APP, false)));

    assertEquals(0, deliveryCount(notifyUser(orgA, user)), "suppressed in org A");
    assertEquals(1, deliveryCount(notifyUser(orgB, user)), "unaffected in org B");
  }

  /** An EMAIL opt-out must not suppress the in-app channel a staff user actually receives. */
  @Test
  void optOut_isPerChannel() {
    UUID org = createOrg("acme");
    UUID user = createUser("staff@acme.test");
    service.setUserPreferences(
        org, user, List.of(new PreferenceInput("ORDER_PLACED", NotificationChannel.EMAIL, false)));

    assertEquals(1, deliveryCount(notifyUser(org, user)), "email opt-out leaves in_app untouched");
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private boolean enabledFlag(UUID customerId) {
    return dsl.select(NOTIFICATION_PREFERENCE.ENABLED)
        .from(NOTIFICATION_PREFERENCE)
        .where(NOTIFICATION_PREFERENCE.CUSTOMER_ID.eq(customerId))
        .limit(1)
        .fetchOne(NOTIFICATION_PREFERENCE.ENABLED);
  }

  private static String lastSegment(String url) {
    return url.substring(url.lastIndexOf('/') + 1);
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    String m = c.getMessage();
    return m == null ? c.getClass().getSimpleName() : m.split("\n")[0];
  }

  private UUID notifyUser(UUID org, UUID user) {
    return service
        .notify(
            dsl,
            org,
            NotificationRecipient.user(user),
            NotificationType.ORDER_PLACED,
            Map.of("order_number", "SO-1"),
            "sales_order",
            UUID.randomUUID(),
            "/orgs/" + org + "/sales-orders/x")
        .getId();
  }

  private UUID notifyCustomer(UUID org, UUID customer) {
    return service
        .notify(
            dsl,
            org,
            NotificationRecipient.customer(customer),
            NotificationType.ORDER_PLACED,
            Map.of("order_number", "SO-1"),
            "sales_order",
            UUID.randomUUID(),
            "http://localhost:8080/api/public/orders/tok")
        .getId();
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

  private String notificationStatus(UUID id) {
    return dsl.select(NOTIFICATION.STATUS)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(id))
        .fetchOne(NOTIFICATION.STATUS);
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
        .set(APP_USER.ACTIVE, true)
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
}
