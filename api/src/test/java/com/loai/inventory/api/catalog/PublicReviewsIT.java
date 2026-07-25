package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.LISTING_REVIEW;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.api.dto.PublicReviewResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ReviewStatus;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.StorefrontService;
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
 * The anonymous public review read + the listing aggregate (slice R1, AC4–AC5): rows carry exactly
 * {@code {display_name, rating, body?, created_at}} (JSON leak scan), APPROVED only, newest first;
 * the listing detail and list rows carry {@code rating_avg} (one decimal, string) + {@code
 * rating_count}, computed over APPROVED only and <b>absent</b> when zero; a DRAFT listing's reviews
 * are unreachable because the slug resolves through the same PUBLISHED-only resolution as the
 * listing read.
 */
@Testcontainers
class PublicReviewsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ListingReviewService reviews;
  static StorefrontService storefront;
  static ObjectStorage storage;
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
    storage = ObjectStorageFactory.build();

    reviews =
        new ListingReviewService(
            dsl,
            new ListingReviewRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
    storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CategoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
            new ListingReviewRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CollectionRepositoryFactoryImpl(),
            storage,
            null,
            null);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE listing_review, product_listing, product, customer, org RESTART IDENTITY"
            + " CASCADE");
  }

  // AC4: whitelist + ordering

  @Test
  void publicRows_carryExactlyTheWhitelist_noLeak() throws Exception {
    Seed s = seed();
    OffsetDateTime t0 = OffsetDateTime.parse("2026-07-01T09:00:00Z");
    review(s, s.listingId, "Ana", 5, "  <b>ممتاز</b> line1\nline2 ", ReviewStatus.APPROVED, t0);
    review(s, s.listingId, "Badr", 4, null, ReviewStatus.APPROVED, t0.plusHours(1));
    review(s, s.listingId, "Pending", 1, "hidden", ReviewStatus.PENDING, t0.plusHours(2));
    review(s, s.listingId, "Rejected", 1, "hidden", ReviewStatus.REJECTED, t0.plusHours(3));

    ListingReviewService.PublicPage page = reviews.publicPage(s.orgSlug, "kettle", 0, 20);
    assertEquals(2, page.total(), "APPROVED only");
    List<PublicReviewResponse> data =
        page.items().stream().map(PublicReviewResponse::from).toList();
    assertEquals("Badr", data.get(0).getDisplayName(), "newest first");
    assertEquals("Ana", data.get(1).getDisplayName());

    String json =
        mapper.writeValueAsString(new PageResponse<>(data, page.total(), page.page(), page.size()));
    assertTrue(json.contains("\"display_name\""));
    assertTrue(json.contains("\"rating\""));
    assertTrue(json.contains("\"created_at\""));
    assertTrue(json.contains("<b>ممتاز</b> line1\\nline2"), "body crosses verbatim");
    for (String forbidden :
        List.of(
            "\"id\"",
            "customer_id",
            "org_id",
            "product_listing_id",
            "email",
            "\"status\"",
            "updated_at")) {
      assertFalse(json.contains(forbidden), "must not leak " + forbidden + " — got " + json);
    }
  }

  @Test
  void draftListing_reviews_areUnreachable() {
    Seed s = seed();
    UUID draft = listing(s, s.productId2, "wip", ListingStatus.DRAFT);
    review(s, draft, "Ana", 5, null, ReviewStatus.APPROVED, OffsetDateTime.now());

    // The slug resolves through the same PUBLISHED-only resolution as the listing read —
    // unreachable by construction, indistinguishable from unknown.
    assertThrows(NotFoundException.class, () -> reviews.publicPage(s.orgSlug, "wip", 0, 20));
    assertThrows(NotFoundException.class, () -> reviews.publicPage(s.orgSlug, "ghost", 0, 20));
  }

  @Test
  void inactiveOrg_is404() {
    Seed s = seed();
    dsl.update(ORG).set(ORG.ACTIVE, false).where(ORG.ID.eq(s.orgId)).execute();
    assertThrows(NotFoundException.class, () -> reviews.publicPage(s.orgSlug, "kettle", 0, 20));
  }

  @Test
  void paging_isHonored() {
    Seed s = seed();
    OffsetDateTime t0 = OffsetDateTime.parse("2026-07-01T09:00:00Z");
    for (int i = 0; i < 5; i++) {
      review(s, s.listingId, "R" + i, 5, null, ReviewStatus.APPROVED, t0.plusHours(i));
    }
    ListingReviewService.PublicPage page1 = reviews.publicPage(s.orgSlug, "kettle", 1, 2);
    assertEquals(5, page1.total());
    assertEquals(2, page1.items().size());
    assertEquals("R2", page1.items().get(0).getDisplayName(), "page 1 of newest-first");
  }

  // AC5: the aggregate

  @Test
  void aggregate_ratings4and5_yieldAvg4_5_count2_pendingRejectedExcluded() throws Exception {
    Seed s = seed();
    OffsetDateTime now = OffsetDateTime.now();
    review(s, s.listingId, "Ana", 4, null, ReviewStatus.APPROVED, now);
    review(s, s.listingId, "Badr", 5, null, ReviewStatus.APPROVED, now);
    review(s, s.listingId, "Pending", 1, null, ReviewStatus.PENDING, now);
    review(s, s.listingId, "Rejected", 1, null, ReviewStatus.REJECTED, now);

    // Detail carries it.
    StorefrontService.ListingView detail = storefront.getListing(s.orgSlug, "kettle");
    assertEquals("4.5", detail.ratingAvg(), "one-decimal string, PENDING/REJECTED excluded");
    assertEquals(2L, detail.ratingCount());

    // List rows carry it too — same DTO.
    StorefrontService.ListingPage page = storefront.listPublished(s.orgSlug, null, 0, 20);
    StorefrontService.ListingView row =
        page.items().stream().filter(v -> v.slug().equals("kettle")).findFirst().orElseThrow();
    assertEquals("4.5", row.ratingAvg());
    assertEquals(2L, row.ratingCount());

    String json = mapper.writeValueAsString(PublicListingResponse.from(detail));
    assertTrue(json.contains("\"rating_avg\":\"4.5\""), "string, not a float — got " + json);
    assertTrue(json.contains("\"rating_count\":2"));
  }

  @Test
  void zeroApproved_aggregateAbsent_neverZeroFabricated() throws Exception {
    Seed s = seed();
    review(s, s.listingId, "Pending", 5, null, ReviewStatus.PENDING, OffsetDateTime.now());

    StorefrontService.ListingView detail = storefront.getListing(s.orgSlug, "kettle");
    assertNull(detail.ratingAvg());
    assertNull(detail.ratingCount());

    String json = mapper.writeValueAsString(PublicListingResponse.from(detail));
    assertFalse(json.contains("rating_avg"), "absent, never zero — got " + json);
    assertFalse(json.contains("rating_count"));
  }

  @Test
  void aggregate_isPerListing_notCrossContaminated() {
    Seed s = seed();
    UUID other = listing(s, s.productId2, "gopro", ListingStatus.PUBLISHED);
    review(s, s.listingId, "Ana", 2, null, ReviewStatus.APPROVED, OffsetDateTime.now());
    review(s, other, "Badr", 5, null, ReviewStatus.APPROVED, OffsetDateTime.now());

    StorefrontService.ListingPage page = storefront.listPublished(s.orgSlug, null, 0, 20);
    var kettle =
        page.items().stream().filter(v -> v.slug().equals("kettle")).findFirst().orElseThrow();
    var gopro =
        page.items().stream().filter(v -> v.slug().equals("gopro")).findFirst().orElseThrow();
    assertEquals("2.0", kettle.ratingAvg());
    assertEquals(1L, kettle.ratingCount());
    assertEquals("5.0", gopro.ratingAvg());
    assertEquals(1L, gopro.ratingCount());
  }

  // ───────── seeding ─────────

  private record Seed(
      UUID orgId, String orgSlug, UUID productId, UUID productId2, UUID listingId) {}

  private Seed seed() {
    UUID orgId = UUID.randomUUID();
    String slug = "store-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    UUID p1 = product(orgId);
    UUID p2 = product(orgId);
    Seed partial = new Seed(orgId, slug, p1, p2, null);
    UUID listingId = listing(partial, p1, "kettle", ListingStatus.PUBLISHED);
    return new Seed(orgId, slug, p1, p2, listingId);
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

  private UUID listing(Seed s, UUID productId, String slug, ListingStatus status) {
    ProductListing l = new ProductListing();
    l.setOrgId(s.orgId());
    l.setProductId(productId);
    l.setTitle(slug + " title");
    l.setSlug(slug);
    l.setSalesPrice(new BigDecimal("19.99"));
    l.setStatus(status);
    l.setPublishedAt(status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null);
    return new ProductListingRepositoryFactoryImpl().create(dsl).insert(l).getId();
  }

  /** Direct row insert with a fresh customer per review (the unique key is per customer). */
  private void review(
      Seed s,
      UUID listingId,
      String displayName,
      int rating,
      String body,
      ReviewStatus status,
      OffsetDateTime at) {
    UUID reviewer = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, reviewer)
        .set(CUSTOMER.ORG_ID, s.orgId())
        .set(CUSTOMER.NAME, displayName)
        .set(CUSTOMER.EMAIL, "r-" + reviewer + "@acme.test")
        .execute();
    dsl.insertInto(LISTING_REVIEW)
        .set(LISTING_REVIEW.ID, UUID.randomUUID())
        .set(LISTING_REVIEW.ORG_ID, s.orgId())
        .set(LISTING_REVIEW.PRODUCT_LISTING_ID, listingId)
        .set(LISTING_REVIEW.CUSTOMER_ID, reviewer)
        .set(LISTING_REVIEW.RATING, (short) rating)
        .set(LISTING_REVIEW.BODY, body)
        .set(LISTING_REVIEW.DISPLAY_NAME, displayName)
        .set(LISTING_REVIEW.STATUS, status.name())
        .set(LISTING_REVIEW.CREATED_AT, at)
        .set(LISTING_REVIEW.UPDATED_AT, at)
        .execute();
  }
}
