package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT_LINE;
import static com.loai.inventory.repository.generated.Tables.LISTING_REVIEW;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ReviewStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.ListingReviewService.Submitted;
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
 * Portal review writes (slice R1, {@code stories/storefront_reviews.md}) against real Postgres,
 * driving {@link ListingReviewService}. Covers:
 *
 * <ul>
 *   <li>AC1 — eligibility: DELIVERED fulfillment line → submit ok; un-bought → 403 cause-naming;
 *       bought-but-not-delivered (PENDING/SHIPPED fulfillment, or no fulfillment at all) → 403;
 *       unknown slug → opaque 404.
 *   <li>AC2 — upsert-edit: a second submit replaces rating/body, resets APPROVED → PENDING, and
 *       never grows the row count.
 *   <li>AC6 — own-delete removes the row; a foreign customer's id → the same opaque 404.
 *   <li>AC8 — body &gt; 2000 → 400, rating outside 1–5 → 400, body stored byte-verbatim.
 *   <li>R1 rider — the portal order detail carries per-line {@code delivered} + {@code
 *       listing_slug} for the "rate this item" entry.
 * </ul>
 */
@Testcontainers
class PortalReviewsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ListingReviewService service;
  static CustomerPortalService portalService;
  static SalesOrderRepositoryFactoryImpl salesOrderRepositoryFactory;

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

    salesOrderRepositoryFactory = new SalesOrderRepositoryFactoryImpl();
    service =
        new ListingReviewService(
            dsl,
            new ListingReviewRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
    portalService =
        new CustomerPortalService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            salesOrderRepositoryFactory,
            new SalesInvoiceRepositoryFactoryImpl(),
            new CustomerAddressRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new FulfillmentRepositoryFactoryImpl(),
            null);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE listing_review, fulfillment_line, fulfillment, sales_order_line, sales_order,"
            + " product_listing, product, customer, org RESTART IDENTITY CASCADE");
  }

  // AC1: the verified-purchase gate

  @Test
  void deliveredPurchase_canSubmit_landsPending() {
    Seed s = seed();
    deliverProduct(s, s.productId, 1);

    Submitted result = service.submit(s.orgId, s.customerId, "kettle", 5, "Great kettle");

    assertTrue(result.created(), "first submit is a fresh 201");
    assertEquals(ReviewStatus.PENDING, result.review().getStatus(), "lands PENDING, never public");
    assertEquals(5, result.review().getRating());
    assertEquals("Great kettle", result.review().getBody());
    assertEquals("Hussin", result.review().getDisplayName(), "display name frozen from customer");
  }

  @Test
  void unBoughtListing_is403_withCauseNamingMessage() {
    Seed s = seed();
    deliverProduct(s, s.productId, 1); // the kettle arrived...
    listing(s.orgId, s.productId2, "gopro", ListingStatus.PUBLISHED); // ...the GoPro did not

    AuthorizationException e =
        assertThrows(
            AuthorizationException.class,
            () -> service.submit(s.orgId, s.customerId, "gopro", 4, null));
    assertTrue(e.getMessage().contains("after they're delivered"), "cause-naming 403");
  }

  @Test
  void boughtButNotDelivered_is403() {
    Seed s = seed();
    // An order with a line exists, its fulfillment is only SHIPPED — goods not in hand.
    listing(s.orgId, s.productId, "kettle", ListingStatus.PUBLISHED);
    UUID lineId = order(s, s.productId, 2);
    fulfillment(s, lineId, 2, "SHIPPED");
    assertThrows(
        AuthorizationException.class,
        () -> service.submit(s.orgId, s.customerId, "kettle", 4, null));

    // A PAID order with no fulfillment at all is equally ineligible.
    Seed s2 = seed();
    listing(s2.orgId, s2.productId, "kettle", ListingStatus.PUBLISHED);
    order(s2, s2.productId, 1);
    assertThrows(
        AuthorizationException.class,
        () -> service.submit(s2.orgId, s2.customerId, "kettle", 4, null));
  }

  @Test
  void unknownListingSlug_isOpaque404() {
    Seed s = seed();
    assertThrows(
        NotFoundException.class, () -> service.submit(s.orgId, s.customerId, "ghost", 4, null));
  }

  @Test
  void crossOrg_slugResolution_isOrgScoped() {
    Seed a = seed();
    deliverProduct(a, a.productId, 1); // "kettle" exists (and is delivered) only in org A
    Seed b = seed();
    // Org B's customer asking org B for "kettle" hits the same opaque 404 as any unknown slug.
    assertThrows(
        NotFoundException.class, () -> service.submit(b.orgId, b.customerId, "kettle", 4, null));
  }

  // AC2: upsert-edit

  @Test
  void resubmit_replacesContent_resetsToPending_rowCountStable() {
    Seed s = seed();
    deliverProduct(s, s.productId, 1);

    Submitted first = service.submit(s.orgId, s.customerId, "kettle", 5, "original");
    // The merchant approves it.
    service.moderate(s.orgId, first.review().getId(), ReviewStatus.APPROVED);

    Submitted second = service.submit(s.orgId, s.customerId, "kettle", 2, "edited");
    assertFalse(second.created(), "an edit replays as 200, not a fresh row");
    assertEquals(first.review().getId(), second.review().getId(), "same row, upserted");
    assertEquals(2, second.review().getRating());
    assertEquals("edited", second.review().getBody());
    assertEquals(
        ReviewStatus.PENDING, second.review().getStatus(), "edit = re-moderation (epic §3)");
    assertEquals(1, dsl.fetchCount(LISTING_REVIEW), "the row count never grows");
  }

  // AC6: own-delete

  @Test
  void deleteOwn_removes_foreignIdIsOpaque404() {
    Seed s = seed();
    deliverProduct(s, s.productId, 1);
    Submitted mine = service.submit(s.orgId, s.customerId, "kettle", 5, null);

    UUID stranger = customer(s.orgId, "stranger@acme.test", "Stranger");
    assertThrows(
        NotFoundException.class,
        () -> service.deleteOwn(s.orgId, stranger, mine.review().getId()),
        "a foreign customer deleting my review gets the same opaque 404");

    service.deleteOwn(s.orgId, s.customerId, mine.review().getId());
    assertEquals(0, dsl.fetchCount(LISTING_REVIEW));
    assertThrows(
        NotFoundException.class,
        () -> service.deleteOwn(s.orgId, s.customerId, mine.review().getId()),
        "deleting an already-deleted id is the same 404");
  }

  // AC8: validation + verbatim storage

  @Test
  void validation_ratingBounds_bodyCap() {
    Seed s = seed();
    deliverProduct(s, s.productId, 1);

    assertThrows(
        ValidationException.class, () -> service.submit(s.orgId, s.customerId, "kettle", 0, null));
    assertThrows(
        ValidationException.class, () -> service.submit(s.orgId, s.customerId, "kettle", 6, null));
    assertThrows(
        ValidationException.class,
        () -> service.submit(s.orgId, s.customerId, "kettle", null, null));
    assertThrows(
        ValidationException.class,
        () -> service.submit(s.orgId, s.customerId, "kettle", 4, "x".repeat(2001)));
    // Exactly at the cap is fine.
    service.submit(s.orgId, s.customerId, "kettle", 4, "x".repeat(2000));
  }

  @Test
  void body_isStoredVerbatim_htmlAndArabicIntact() {
    Seed s = seed();
    deliverProduct(s, s.productId, 1);
    String nasty = "  <b>ممتاز</b>\n&lt;script&gt; **bold** \t ";

    Submitted r = service.submit(s.orgId, s.customerId, "kettle", 4, nasty);
    assertEquals(nasty, r.review().getBody(), "inertness is storage-level: byte-verbatim");
  }

  @Test
  void myReviews_newestFirst_withListingIdentity() {
    Seed s = seed();
    deliverProduct(s, s.productId, 1);
    deliverProduct(s, s.productId2, 1, "gopro");
    service.submit(s.orgId, s.customerId, "kettle", 5, null);
    service.submit(s.orgId, s.customerId, "gopro", 3, null);

    var mine = service.myReviews(s.orgId, s.customerId);
    assertEquals(2, mine.size());
    assertEquals("gopro", mine.get(0).listingSlug(), "newest first");
    assertEquals("gopro title", mine.get(0).listingTitle());
    assertEquals("kettle", mine.get(1).listingSlug());
  }

  // R1 rider: the portal order detail carries delivered + listing_slug per line

  @Test
  void orderDetail_carriesDeliveredFlag_andListingSlug() {
    Seed s = seed();
    listing(s.orgId, s.productId, "kettle", ListingStatus.PUBLISHED);
    UUID deliveredLine = order(s, s.productId, 1, "SO-9001");
    fulfillment(s, deliveredLine, 1, "DELIVERED");
    // A second order line never fulfilled.
    UUID pendingLine = order(s, s.productId2, 1, "SO-9002");
    listing(s.orgId, s.productId2, "gopro", ListingStatus.DRAFT);

    CustomerPortalService.OrderDetail delivered =
        portalService.getOrderDetail(s.orgId, s.customerId, "SO-9001");
    assertTrue(delivered.deliveredLineIds().contains(deliveredLine));
    assertEquals("kettle", delivered.listingSlugByProduct().get(s.productId));

    CustomerPortalService.OrderDetail pending =
        portalService.getOrderDetail(s.orgId, s.customerId, "SO-9002");
    assertFalse(pending.deliveredLineIds().contains(pendingLine), "unfulfilled line: no flag");
    assertEquals(
        "gopro",
        pending.listingSlugByProduct().get(s.productId2),
        "slug resolves regardless of listing status — a delivered item stays reviewable");
    assertNull(pending.listingSlugByProduct().get(UUID.randomUUID()));
  }

  // ───────── seeding ─────────

  private record Seed(
      UUID orgId, String orgSlug, UUID customerId, UUID productId, UUID productId2) {}

  private Seed seed() {
    UUID orgId = UUID.randomUUID();
    String slug = "store-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    UUID customerId = customer(orgId, "hussin@acme.test", "Hussin");
    return new Seed(orgId, slug, customerId, product(orgId), product(orgId));
  }

  private UUID customer(UUID orgId, String email, String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, name)
        .set(CUSTOMER.EMAIL, email + "-" + id)
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

  /** One placed order with one line for {@code productId}; returns the line id. */
  private UUID order(Seed s, UUID productId, int qty) {
    return order(s, productId, qty, "SO-" + UUID.randomUUID().toString().substring(0, 8));
  }

  private UUID order(Seed s, UUID productId, int qty, String orderNumber) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    SalesOrder order =
        SalesOrder.rehydrate(
            orderId,
            s.orgId,
            s.customerId,
            orderNumber,
            OrderChannel.ONLINE,
            "EGP",
            UUID.randomUUID().toString(),
            now,
            OrderStatus.PAID,
            new BigDecimal("20.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("20.00"),
            new BigDecimal("20.00"),
            now,
            now,
            null,
            null,
            null,
            null,
            null,
            null);
    List<SalesOrderLine> lines =
        List.of(
            SalesOrderLine.rehydrate(
                lineId,
                orderId,
                productId,
                "Widget",
                qty,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("20.00"),
                BigDecimal.ZERO,
                new BigDecimal("20.00")));
    salesOrderRepositoryFactory.create(dsl).insert(order, lines);
    return lineId;
  }

  private void fulfillment(Seed s, UUID salesOrderLineId, int qty, String status) {
    UUID orderId =
        dsl.select(com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE.SALES_ORDER_ID)
            .from(com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE)
            .where(
                com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE.ID.eq(
                    salesOrderLineId))
            .fetchSingle()
            .value1();
    UUID fid = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fid)
        .set(FULFILLMENT.ORG_ID, s.orgId)
        .set(FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(
            FULFILLMENT.STATUS,
            com.loai.inventory.repository.generated.enums.FulfillmentStatus.valueOf(status))
        .execute();
    dsl.insertInto(FULFILLMENT_LINE)
        .set(FULFILLMENT_LINE.ID, UUID.randomUUID())
        .set(FULFILLMENT_LINE.FULFILLMENT_ID, fid)
        .set(FULFILLMENT_LINE.SALES_ORDER_LINE_ID, salesOrderLineId)
        .set(FULFILLMENT_LINE.QUANTITY, qty)
        .execute();
  }

  /** The full happy spine: listing "kettle" + a PAID order + a DELIVERED fulfillment line. */
  private void deliverProduct(Seed s, UUID productId, int qty) {
    deliverProduct(s, productId, qty, "kettle");
  }

  private void deliverProduct(Seed s, UUID productId, int qty, String slug) {
    listing(s.orgId, productId, slug, ListingStatus.PUBLISHED);
    UUID lineId = order(s, productId, qty);
    fulfillment(s, lineId, qty, "DELIVERED");
  }
}
