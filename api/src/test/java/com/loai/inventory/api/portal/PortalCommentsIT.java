package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.LISTING_COMMENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CommentStatus;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingCommentRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.ListingCommentService.Submitted;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * Portal comment writes + "my questions" (slice R2, AC1 + AC5): a logged-in customer asks with no
 * purchase gate; body 1..1000 (400, verbatim); the 6th PENDING question on one listing → 400;
 * unknown slug → opaque 404; mine lists status + reply newest-first; own-delete removes an ANSWERED
 * pair from the public read; a foreign id → the same opaque 404.
 */
@Testcontainers
class PortalCommentsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ListingCommentService service;

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
        new ListingCommentService(
            dsl,
            new ListingCommentRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            TestWiring.notificationService(dsl));
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE notification_delivery_email, notification_delivery_in_app,"
            + " notification_delivery, notification, customer_magic_token, listing_comment,"
            + " product_listing, product, app_user, customer, org RESTART IDENTITY CASCADE");
  }

  // AC1: submit

  @Test
  void loggedIn_noPurchaseNeeded_landsPending() {
    Seed s = seed();
    Submitted result =
        service.submit(s.orgId, s.customerId, "kettle", "Does it come with the cordless base?");

    assertEquals(CommentStatus.PENDING, result.comment().getStatus());
    assertEquals("Does it come with the cordless base?", result.comment().getBody());
    assertEquals("Hussin", result.comment().getDisplayName(), "display name frozen at write");
    assertEquals("kettle", result.listingSlug());
    assertNull(result.comment().getReplyBody(), "no reply yet");
  }

  @Test
  void bodyValidation_emptyAndOverCap_are400_verbatimOtherwise() {
    Seed s = seed();
    assertThrows(
        ValidationException.class, () -> service.submit(s.orgId, s.customerId, "kettle", null));
    assertThrows(
        ValidationException.class, () -> service.submit(s.orgId, s.customerId, "kettle", ""));
    assertThrows(
        ValidationException.class,
        () -> service.submit(s.orgId, s.customerId, "kettle", "x".repeat(1001)));

    String nasty = "  <b>سؤال</b>\nline2 **md** ";
    Submitted r = service.submit(s.orgId, s.customerId, "kettle", nasty);
    assertEquals(nasty, r.comment().getBody(), "stored byte-verbatim");
    // Exactly at the cap is fine.
    service.submit(s.orgId, s.customerId, "kettle", "y".repeat(1000));
  }

  @Test
  void sixthPendingOnOneListing_is400_answeredOnesDoNotCount() {
    Seed s = seed();
    for (int i = 0; i < 5; i++) {
      service.submit(s.orgId, s.customerId, "kettle", "q" + i);
    }
    assertThrows(
        ValidationException.class, () -> service.submit(s.orgId, s.customerId, "kettle", "q6"));

    // Answer one — the cap frees up (it counts PENDING, not history).
    UUID oldest =
        dsl.select(LISTING_COMMENT.ID)
            .from(LISTING_COMMENT)
            .orderBy(LISTING_COMMENT.CREATED_AT.asc())
            .limit(1)
            .fetchSingle()
            .value1();
    service.reply(s.orgId, s.staffUserId, oldest, "Yes it does");
    service.submit(s.orgId, s.customerId, "kettle", "q6 now fits");

    // A different listing has its own budget.
    listing(s.orgId, product(s.orgId), "gopro", ListingStatus.PUBLISHED);
    service.submit(s.orgId, s.customerId, "gopro", "different listing");
  }

  @Test
  void unknownSlug_isOpaque404() {
    Seed s = seed();
    assertThrows(
        NotFoundException.class, () -> service.submit(s.orgId, s.customerId, "ghost", "hi"));
  }

  // AC5: mine + own-delete

  @Test
  void mine_newestFirst_withStatusAndReply() {
    Seed s = seed();
    Submitted first = service.submit(s.orgId, s.customerId, "kettle", "first question");
    service.submit(s.orgId, s.customerId, "kettle", "second question");
    service.reply(s.orgId, s.staffUserId, first.comment().getId(), "the answer");

    var mine = service.myComments(s.orgId, s.customerId);
    assertEquals(2, mine.size());
    assertEquals("second question", mine.get(0).comment().getBody(), "newest first");
    assertEquals(CommentStatus.PENDING, mine.get(0).comment().getStatus());
    assertEquals("kettle title", mine.get(0).listingTitle());
    assertEquals(CommentStatus.ANSWERED, mine.get(1).comment().getStatus());
    assertEquals("the answer", mine.get(1).comment().getReplyBody(), "the author sees the reply");
  }

  @Test
  void deleteOwn_removesAnsweredPairFromPublic_foreignIdIs404() {
    Seed s = seed();
    Submitted q = service.submit(s.orgId, s.customerId, "kettle", "will be answered");
    service.reply(s.orgId, s.staffUserId, q.comment().getId(), "answered");
    assertEquals(1, service.publicPage(s.orgSlug, "kettle", 0, 20).total(), "pair is public");

    UUID stranger = customer(s.orgId, "Stranger");
    assertThrows(
        NotFoundException.class,
        () -> service.deleteOwn(s.orgId, stranger, q.comment().getId()),
        "a foreign customer deleting my question gets the same opaque 404");

    service.deleteOwn(s.orgId, s.customerId, q.comment().getId());
    assertEquals(
        0,
        service.publicPage(s.orgSlug, "kettle", 0, 20).total(),
        "deleting mine removes the public pair too — the customer owns their words");
    assertThrows(
        NotFoundException.class,
        () -> service.deleteOwn(s.orgId, s.customerId, q.comment().getId()));
  }

  // ───────── seeding ─────────

  record Seed(UUID orgId, String orgSlug, UUID customerId, UUID staffUserId, UUID listingId) {}

  private Seed seed() {
    UUID orgId = UUID.randomUUID();
    String slug = "store-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    UUID customerId = customer(orgId, "Hussin");
    UUID staffId = staffUser();
    UUID listingId = listing(orgId, product(orgId), "kettle", ListingStatus.PUBLISHED);
    return new Seed(orgId, slug, customerId, staffId, listingId);
  }

  private UUID customer(UUID orgId, String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, name)
        .set(CUSTOMER.EMAIL, "c-" + id + "@acme.test")
        .execute();
    return id;
  }

  private UUID staffUser() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-staff@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    return id;
  }

  private UUID product(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, "Widget")
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .set(PRODUCT.SKU, "SKU-" + id)
        .execute();
    return id;
  }

  private UUID listing(UUID orgId, UUID productId, String slug, ListingStatus status) {
    ProductListing l = new ProductListing();
    l.setOrgId(orgId);
    l.setProductId(productId);
    l.setTitle(slug + " title");
    l.setSlug(slug);
    l.setSalesPrice(new BigDecimal("19.99"));
    l.setStatus(status);
    l.setPublishedAt(status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null);
    UUID listingId = new ProductListingRepositoryFactoryImpl().create(dsl).insert(l).getId();
    // The listing title (worklist + COMMENT_REPLIED payload) resolves from the default-locale
    // translation row (L6 — the legacy product_listing.title column is gone).
    for (String lang : new String[] {"ar", "en"}) {
      dsl.insertInto(PRODUCT_LISTING_TRANSLATION)
          .set(PRODUCT_LISTING_TRANSLATION.LISTING_ID, listingId)
          .set(PRODUCT_LISTING_TRANSLATION.LANGUAGE, lang)
          .set(PRODUCT_LISTING_TRANSLATION.TITLE, slug + " title")
          .execute();
    }
    return listingId;
  }
}
