package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.LISTING_COMMENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PublicCommentResponse;
import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.CommentStatus;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingCommentRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.ListingCommentService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * The anonymous public Q&amp;A read (slice R2, AC4): ANSWERED pairs only (PENDING/DISMISSED
 * fixtures absent), newest first, paged; rows carry exactly {@code {display_name, body, created_at,
 * reply_body, replied_at}} — never {@code customer_id}/{@code replied_by}/ids (JSON leak scan); a
 * DRAFT listing's Q&amp;A is unreachable because the slug resolves through the same PUBLISHED-only
 * resolution as the listing read.
 */
@Testcontainers
class PublicCommentsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ListingCommentService service;
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
        "TRUNCATE listing_comment, product_listing, product, app_user, customer, org RESTART"
            + " IDENTITY CASCADE");
  }

  @Test
  void answeredOnly_newestFirst_whitelisted_noLeak() throws Exception {
    Seed s = seed();
    OffsetDateTime t0 = OffsetDateTime.parse("2026-07-01T09:00:00Z");
    comment(s, "Ana", "  <b>سؤال</b>\nline2 ", "  the <i>answer</i> ", CommentStatus.ANSWERED, t0);
    comment(s, "Badr", "second q", "second a", CommentStatus.ANSWERED, t0.plusHours(1));
    comment(s, "Hidden1", "pending q", null, CommentStatus.PENDING, t0.plusHours(2));
    comment(s, "Hidden2", "dismissed q", null, CommentStatus.DISMISSED, t0.plusHours(3));

    ListingCommentService.PublicPage page = service.publicPage(s.orgSlug, "kettle", 0, 20);
    assertEquals(2, page.total(), "ANSWERED only — PENDING/DISMISSED never serve");
    List<PublicCommentResponse> data =
        page.items().stream().map(PublicCommentResponse::from).toList();
    assertEquals("Badr", data.get(0).getDisplayName(), "newest first");
    assertEquals("Ana", data.get(1).getDisplayName());

    String json =
        mapper.writeValueAsString(new PageResponse<>(data, page.total(), page.page(), page.size()));
    assertTrue(json.contains("\"display_name\""));
    assertTrue(json.contains("\"reply_body\""));
    assertTrue(json.contains("\"replied_at\""));
    assertTrue(json.contains("<b>سؤال</b>\\nline2"), "the question crosses verbatim");
    assertTrue(json.contains("the <i>answer</i>"), "the answer crosses verbatim");
    for (String forbidden :
        List.of(
            "\"id\"",
            "customer_id",
            "org_id",
            "product_listing_id",
            "replied_by",
            "email",
            "\"status\"")) {
      assertFalse(json.contains(forbidden), "must not leak " + forbidden + " — got " + json);
    }
  }

  @Test
  void draftListing_qna_isUnreachable() {
    Seed s = seed();
    UUID draftListing = listing(s.orgId, product(s.orgId), "wip", ListingStatus.DRAFT);
    UUID asker = customer(s.orgId, "Ana");
    insertComment(
        s.orgId,
        draftListing,
        asker,
        "Ana",
        "q",
        "a",
        CommentStatus.ANSWERED,
        OffsetDateTime.now());

    assertThrows(NotFoundException.class, () -> service.publicPage(s.orgSlug, "wip", 0, 20));
    assertThrows(NotFoundException.class, () -> service.publicPage(s.orgSlug, "ghost", 0, 20));
  }

  @Test
  void paging_isHonored() {
    Seed s = seed();
    OffsetDateTime t0 = OffsetDateTime.parse("2026-07-01T09:00:00Z");
    for (int i = 0; i < 5; i++) {
      comment(s, "C" + i, "q" + i, "a" + i, CommentStatus.ANSWERED, t0.plusHours(i));
    }
    ListingCommentService.PublicPage page1 = service.publicPage(s.orgSlug, "kettle", 1, 2);
    assertEquals(5, page1.total());
    assertEquals(2, page1.items().size());
    assertEquals("q2", page1.items().get(0).getBody(), "page 1 of newest-first");
  }

  // ───────── seeding ─────────

  record Seed(UUID orgId, String orgSlug, UUID listingId) {}

  private Seed seed() {
    UUID orgId = UUID.randomUUID();
    String slug = "store-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    UUID listingId = listing(orgId, product(orgId), "kettle", ListingStatus.PUBLISHED);
    return new Seed(orgId, slug, listingId);
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
    return new ProductListingRepositoryFactoryImpl().create(dsl).insert(l).getId();
  }

  /** A comment on the seed's kettle with a fresh asker. */
  private void comment(
      Seed s,
      String displayName,
      String body,
      String reply,
      CommentStatus status,
      OffsetDateTime at) {
    UUID asker = customer(s.orgId, displayName);
    insertComment(s.orgId, s.listingId, asker, displayName, body, reply, status, at);
  }

  /** Direct row insert; ANSWERED rows get a replied_by staff user + replied_at (the CHECK). */
  private void insertComment(
      UUID orgId,
      UUID listingId,
      UUID customerId,
      String displayName,
      String body,
      String reply,
      CommentStatus status,
      OffsetDateTime at) {
    UUID repliedBy = null;
    if (status == CommentStatus.ANSWERED) {
      repliedBy = UUID.randomUUID();
      dsl.insertInto(APP_USER)
          .set(APP_USER.ID, repliedBy)
          .set(APP_USER.EMAIL, repliedBy + "-staff@acme.test")
          .set(APP_USER.PASSWORD_HASH, "x")
          .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
          .execute();
    }
    dsl.insertInto(LISTING_COMMENT)
        .set(LISTING_COMMENT.ID, UUID.randomUUID())
        .set(LISTING_COMMENT.ORG_ID, orgId)
        .set(LISTING_COMMENT.PRODUCT_LISTING_ID, listingId)
        .set(LISTING_COMMENT.CUSTOMER_ID, customerId)
        .set(LISTING_COMMENT.BODY, body)
        .set(LISTING_COMMENT.DISPLAY_NAME, displayName)
        .set(LISTING_COMMENT.REPLY_BODY, status == CommentStatus.ANSWERED ? reply : null)
        .set(LISTING_COMMENT.REPLIED_BY, repliedBy)
        .set(LISTING_COMMENT.REPLIED_AT, status == CommentStatus.ANSWERED ? at : null)
        .set(LISTING_COMMENT.STATUS, status.name())
        .set(LISTING_COMMENT.CREATED_AT, at)
        .execute();
  }
}
