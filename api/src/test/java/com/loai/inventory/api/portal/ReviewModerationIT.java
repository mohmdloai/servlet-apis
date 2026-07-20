package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.LISTING_REVIEW;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ReviewStatus;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.ListingReviewService.AdminPage;
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
 * Staff review moderation (slice R1, AC3) — the worklist conventions: {@code ?status=PENDING} =
 * queue oldest-first, unfiltered = ledger newest-first, unknown status → 400; approve → the row
 * serves publicly and moves the aggregate; reject → never public; unknown id in-org → 404; admin
 * rows carry customer name/email + listing title.
 */
@Testcontainers
class ReviewModerationIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ListingReviewService service;

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
        new ListingReviewService(
            dsl,
            new ListingReviewRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE listing_review, product_listing, product, customer, org RESTART IDENTITY"
            + " CASCADE");
  }

  @Test
  void queue_isOldestFirst_ledger_isNewestFirst() {
    Seed s = seed();
    OffsetDateTime t0 = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    insertReview(s, "Ana", t0, ReviewStatus.PENDING);
    insertReview(s, "Badr", t0.plusHours(1), ReviewStatus.PENDING);
    insertReview(s, "Cleo", t0.plusHours(2), ReviewStatus.APPROVED);

    AdminPage queue = service.adminList(s.orgId, "PENDING", 0, 20);
    assertEquals(2, queue.total());
    assertEquals(
        List.of("Ana", "Badr"),
        queue.items().stream().map(r -> r.review().getDisplayName()).toList(),
        "the queue is FIFO — oldest first");

    AdminPage ledger = service.adminList(s.orgId, null, 0, 20);
    assertEquals(3, ledger.total());
    assertEquals(
        List.of("Cleo", "Badr", "Ana"),
        ledger.items().stream().map(r -> r.review().getDisplayName()).toList(),
        "the unfiltered ledger is newest first");
  }

  @Test
  void unknownStatus_is400() {
    Seed s = seed();
    assertThrows(ValidationException.class, () -> service.adminList(s.orgId, "SHINY", 0, 20));
  }

  @Test
  void adminRows_carryCustomerContext_andListingTitle() {
    Seed s = seed();
    insertReview(s, "Ana", OffsetDateTime.now(), ReviewStatus.PENDING);

    var row = service.adminList(s.orgId, "PENDING", 0, 20).items().get(0);
    assertEquals("Ana", row.customerName(), "staff see CRM context");
    assertTrue(row.customerEmail().contains("@"), "email present for staff");
    assertEquals("kettle title", row.listingTitle());
  }

  @Test
  void approve_publishes_reject_neverPublic_movesAggregate() {
    Seed s = seed();
    UUID approved = insertReview(s, "Ana", OffsetDateTime.now(), ReviewStatus.PENDING, 4);
    UUID rejected = insertReview(s, "Badr", OffsetDateTime.now(), ReviewStatus.PENDING, 1);

    service.moderate(s.orgId, approved, ReviewStatus.APPROVED);
    service.moderate(s.orgId, rejected, ReviewStatus.REJECTED);

    var page = service.publicPage(s.orgSlug, "kettle", 0, 20);
    assertEquals(1, page.total(), "only the approved row serves publicly");
    assertEquals("Ana", page.items().get(0).getDisplayName());

    var aggregates =
        new ListingReviewRepositoryFactoryImpl()
            .create(dsl)
            .findAggregates(s.orgId, List.of(s.listingId));
    assertEquals(1, aggregates.get(s.listingId).count(), "REJECTED never counts");
  }

  @Test
  void moderate_unknownId_is404_andDecisionValidated() {
    Seed s = seed();
    assertThrows(
        NotFoundException.class,
        () -> service.moderate(s.orgId, UUID.randomUUID(), ReviewStatus.APPROVED));
    UUID id = insertReview(s, "Ana", OffsetDateTime.now(), ReviewStatus.PENDING);
    assertThrows(
        ValidationException.class, () -> service.moderate(s.orgId, id, ReviewStatus.PENDING));
    // Cross-org: the same id under a foreign org is the same 404.
    Seed other = seed();
    assertThrows(
        NotFoundException.class, () -> service.moderate(other.orgId, id, ReviewStatus.APPROVED));
  }

  // ───────── seeding ─────────

  private record Seed(
      UUID orgId, String orgSlug, UUID customerId, UUID productId, UUID listingId) {}

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
        .set(CUSTOMER.EMAIL, "hussin@acme.test-" + customerId)
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
    UUID listingId = new ProductListingRepositoryFactoryImpl().create(dsl).insert(l).getId();
    // The worklist title is resolved from the default-locale translation row (L6 — the legacy
    // product_listing.title column is gone). Seed both locales the same value.
    for (String lang : new String[] {"ar", "en"}) {
      dsl.insertInto(PRODUCT_LISTING_TRANSLATION)
          .set(PRODUCT_LISTING_TRANSLATION.LISTING_ID, listingId)
          .set(PRODUCT_LISTING_TRANSLATION.LANGUAGE, lang)
          .set(PRODUCT_LISTING_TRANSLATION.TITLE, "kettle title")
          .execute();
    }
    return new Seed(orgId, slug, customerId, productId, listingId);
  }

  private UUID insertReview(Seed s, String displayName, OffsetDateTime at, ReviewStatus status) {
    return insertReview(s, displayName, at, status, 4);
  }

  /** Direct row insert — a distinct customer per review (the unique key is per customer). */
  private UUID insertReview(
      Seed s, String displayName, OffsetDateTime at, ReviewStatus status, int rating) {
    UUID reviewer = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, reviewer)
        .set(CUSTOMER.ORG_ID, s.orgId)
        .set(CUSTOMER.NAME, displayName)
        .set(CUSTOMER.EMAIL, "hussin@" + displayName.toLowerCase() + "-" + reviewer + ".test")
        .execute();
    UUID id = UUID.randomUUID();
    dsl.insertInto(LISTING_REVIEW)
        .set(LISTING_REVIEW.ID, id)
        .set(LISTING_REVIEW.ORG_ID, s.orgId)
        .set(LISTING_REVIEW.PRODUCT_LISTING_ID, s.listingId)
        .set(LISTING_REVIEW.CUSTOMER_ID, reviewer)
        .set(LISTING_REVIEW.RATING, (short) rating)
        .set(LISTING_REVIEW.DISPLAY_NAME, displayName)
        .set(LISTING_REVIEW.STATUS, status.name())
        .set(LISTING_REVIEW.CREATED_AT, at)
        .set(LISTING_REVIEW.UPDATED_AT, at)
        .execute();
    return id;
  }
}
