package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CommentStatus;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.ListingComment;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingCommentRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.NotificationService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * The staff answer worklist (slice R2, AC2–AC3 + AC6): a reply publishes the pair, sets ANSWERED ⇔
 * reply present, and raises exactly one {@code COMMENT_REPLIED} notification with an immediate
 * in-app feed row + an email leg honoring the {@code (org,customer)} opt-out; a re-reply edits the
 * answer without re-notifying; dismiss is silent + terminal (reply on DISMISSED → 409); the queue
 * is oldest-first, the ledger newest-first, unknown status → 400.
 */
@Testcontainers
class CommentReplyIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ListingCommentService service;
  static NotificationService notifications;

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

    notifications = TestWiring.notificationService(dsl);
    service =
        new ListingCommentService(
            dsl,
            new ListingCommentRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            notifications);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE notification_preference, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery, notification,"
            + " customer_magic_token, listing_comment, product_listing, product, app_user,"
            + " customer, org RESTART IDENTITY CASCADE");
  }

  // AC2: reply publishes + notifies (in-txn feed row + email leg)

  @Test
  void reply_publishes_setsAnswered_notifiesOnce_withListingSlugPayload() {
    Seed s = seed();
    ListingComment q =
        service.submit(s.orgId, s.customerId, "kettle", "Does it whistle?").comment();

    ListingComment answered = service.reply(s.orgId, s.staffUserId, q.getId(), "Loudly.");

    assertEquals(CommentStatus.ANSWERED, answered.getStatus());
    assertEquals("Loudly.", answered.getReplyBody());
    assertNotNull(answered.getRepliedAt());
    assertEquals(s.staffUserId, answered.getRepliedBy(), "audit column, internal only");

    // The pair is public now — answering IS the publish.
    assertEquals(1, service.publicPage(s.orgSlug, "kettle", 0, 20).total());

    // Exactly one COMMENT_REPLIED for the asker, readable in the feed immediately (in-txn row).
    List<InAppFeedItem> feed = notifications.getCustomerFeed(s.orgId, s.customerId, false, 0, 10);
    assertEquals(1, feed.size());
    assertEquals("COMMENT_REPLIED", feed.get(0).notification().getType());
    // JSONB canonical form may pad after the colon — assert the key + value, not the exact bytes.
    String payload = feed.get(0).notification().getPayloadJson();
    assertTrue(payload.contains("\"listing_slug\""), "payload has the deep-link key: " + payload);
    assertTrue(payload.contains("\"kettle\""), "payload carries the slug value: " + payload);

    // Both channel legs exist (in_app + email — the customer channel set), each PENDING for the
    // sweeper.
    List<String> channels =
        dsl.select(NOTIFICATION_DELIVERY.CHANNEL)
            .from(NOTIFICATION_DELIVERY)
            .join(NOTIFICATION)
            .on(NOTIFICATION.ID.eq(NOTIFICATION_DELIVERY.NOTIFICATION_ID))
            .where(NOTIFICATION.RECIPIENT_CUSTOMER_ID.eq(s.customerId))
            .orderBy(NOTIFICATION_DELIVERY.CHANNEL.asc())
            .fetch(NOTIFICATION_DELIVERY.CHANNEL);
    assertEquals(List.of("email", "in_app"), channels);
  }

  @Test
  void reReply_editsTheAnswer_neverReNotifies() {
    Seed s = seed();
    ListingComment q = service.submit(s.orgId, s.customerId, "kettle", "q").comment();
    service.reply(s.orgId, s.staffUserId, q.getId(), "first answer");
    assertEquals(1, notifications.countCustomerFeed(s.orgId, s.customerId, false));

    ListingComment edited = service.reply(s.orgId, s.staffUserId, q.getId(), "edited answer");
    assertEquals(CommentStatus.ANSWERED, edited.getStatus(), "stays ANSWERED");
    assertEquals("edited answer", edited.getReplyBody());
    assertEquals(
        1,
        notifications.countCustomerFeed(s.orgId, s.customerId, false),
        "editing the published answer never re-notifies");
    assertEquals(
        "edited answer",
        service.publicPage(s.orgSlug, "kettle", 0, 20).items().get(0).getReplyBody(),
        "the public pair carries the updated answer");
  }

  @Test
  void reply_honorsEmailOptOut_feedRowStillLands() {
    Seed s = seed();
    // The customer opted out of email for the org (the same rows one-click unsubscribe writes).
    notifications.unsubscribeCustomerEmail(s.orgId, s.customerId);
    ListingComment q = service.submit(s.orgId, s.customerId, "kettle", "q").comment();

    service.reply(s.orgId, s.staffUserId, q.getId(), "answer");

    List<String> channels =
        dsl.select(NOTIFICATION_DELIVERY.CHANNEL)
            .from(NOTIFICATION_DELIVERY)
            .join(NOTIFICATION)
            .on(NOTIFICATION.ID.eq(NOTIFICATION_DELIVERY.NOTIFICATION_ID))
            .where(NOTIFICATION.RECIPIENT_CUSTOMER_ID.eq(s.customerId))
            .fetch(NOTIFICATION_DELIVERY.CHANNEL);
    assertEquals(List.of("in_app"), channels, "email leg suppressed, feed row kept");
    assertEquals(1, notifications.countCustomerFeed(s.orgId, s.customerId, false));
  }

  @Test
  void replyValidation_bodyRequired_capped_unknownId404() {
    Seed s = seed();
    ListingComment q = service.submit(s.orgId, s.customerId, "kettle", "q").comment();
    assertThrows(
        ValidationException.class, () -> service.reply(s.orgId, s.staffUserId, q.getId(), null));
    assertThrows(
        ValidationException.class, () -> service.reply(s.orgId, s.staffUserId, q.getId(), ""));
    assertThrows(
        ValidationException.class,
        () -> service.reply(s.orgId, s.staffUserId, q.getId(), "x".repeat(2001)));
    assertThrows(
        NotFoundException.class,
        () -> service.reply(s.orgId, s.staffUserId, UUID.randomUUID(), "answer"));
    // Cross-org: the same id under a foreign org is the same 404.
    Seed other = seed();
    assertThrows(
        NotFoundException.class,
        () -> service.reply(other.orgId, other.staffUserId, q.getId(), "answer"));
  }

  // AC3: dismiss is silent + terminal

  @Test
  void dismiss_neverPublic_noNotification_terminal() {
    Seed s = seed();
    ListingComment q = service.submit(s.orgId, s.customerId, "kettle", "q").comment();

    ListingComment dismissed = service.dismiss(s.orgId, q.getId());
    assertEquals(CommentStatus.DISMISSED, dismissed.getStatus());
    assertEquals(0, service.publicPage(s.orgSlug, "kettle", 0, 20).total(), "never public");
    assertEquals(
        0,
        notifications.countCustomerFeed(s.orgId, s.customerId, false),
        "silence, not rejection-nagging");

    // Terminal: no reply, no second dismiss.
    assertThrows(
        ConflictException.class,
        () -> service.reply(s.orgId, s.staffUserId, q.getId(), "too late"));
    assertThrows(ConflictException.class, () -> service.dismiss(s.orgId, q.getId()));
    // An ANSWERED pair can't be dismissed either (the reply is already public).
    ListingComment answered = service.submit(s.orgId, s.customerId, "kettle", "q2").comment();
    service.reply(s.orgId, s.staffUserId, answered.getId(), "a");
    assertThrows(ConflictException.class, () -> service.dismiss(s.orgId, answered.getId()));
  }

  // AC6: queue conventions

  @Test
  void queue_isOldestFirst_ledger_isNewestFirst_unknownStatus400() {
    Seed s = seed();
    OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC);
    ListingComment a = service.submit(s.orgId, s.customerId, "kettle", "first").comment();
    sleepMillis();
    service.submit(s.orgId, s.customerId, "kettle", "second");
    sleepMillis();
    ListingComment c = service.submit(s.orgId, s.customerId, "kettle", "third").comment();
    service.reply(s.orgId, s.staffUserId, c.getId(), "answered");
    assertTrue(a.getCreatedAt().isAfter(t0.minusMinutes(1)));

    var queue = service.adminList(s.orgId, "PENDING", 0, 20);
    assertEquals(2, queue.total());
    assertEquals(
        List.of("first", "second"),
        queue.items().stream().map(r -> r.comment().getBody()).toList(),
        "the queue is FIFO — oldest first");

    var ledger = service.adminList(s.orgId, null, 0, 20);
    assertEquals(3, ledger.total());
    assertEquals(
        "third",
        ledger.items().get(0).comment().getBody(),
        "the unfiltered ledger is newest first");
    assertEquals("Hussin", ledger.items().get(0).customerName(), "staff see CRM context");
    assertEquals("kettle title", ledger.items().get(0).listingTitle());

    assertThrows(ValidationException.class, () -> service.adminList(s.orgId, "SHINY", 0, 20));
  }

  private static void sleepMillis() {
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ───────── seeding ─────────

  record Seed(UUID orgId, String orgSlug, UUID customerId, UUID staffUserId) {}

  private Seed seed() {
    UUID orgId = UUID.randomUUID();
    String slug = "store-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    UUID customerId = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, customerId)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Hussin")
        .set(CUSTOMER.EMAIL, "c-" + customerId + "@acme.test")
        .execute();
    UUID staffId = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, staffId)
        .set(APP_USER.EMAIL, staffId + "-staff@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    UUID productId = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, productId)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, "Kettle")
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .set(PRODUCT.SKU, "SKU-" + productId)
        .execute();
    ProductListing l = new ProductListing();
    l.setOrgId(orgId);
    l.setProductId(productId);
    l.setTitle("kettle title");
    l.setSlug("kettle");
    l.setSalesPrice(new BigDecimal("19.99"));
    l.setStatus(ListingStatus.PUBLISHED);
    l.setPublishedAt(OffsetDateTime.now());
    new ProductListingRepositoryFactoryImpl().create(dsl).insert(l);
    return new Seed(orgId, slug, customerId, staffId);
  }
}
