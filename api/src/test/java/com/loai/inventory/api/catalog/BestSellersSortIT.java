package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.ListingPage;
import com.loai.inventory.service.StorefrontService.ListingView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
 * Best-sellers sort + sold filter ({@code stories/storefront_best_sellers.md}, roadmap item 4):
 * {@code ?sort=best_selling} ranks the PUBLISHED catalog by units actually sold in the rolling
 * 30-day window, and {@code ?sold=true} narrows to listings that genuinely sold in it.
 *
 * <p>The invariants under test are the ones that make the ranking <em>honest</em> and
 * <em>correct</em>: only money-committed orders count (a cancelled or still-unpaid order is not a
 * sale), the window is enforced, the ranking happens in SQL so it survives {@code offset/limit}
 * paging, a never-sold listing is ranked last rather than hidden (only the explicit {@code sold}
 * predicate hides), and no sold count ever crosses the whitelisted DTO boundary — the ordering is
 * the fact, a number would invite fabrication-adjacent styling.
 *
 * <p>Drives {@link StorefrontService} directly (the house pattern for the storefront read ITs, see
 * {@code StorefrontSearchIT}), seeding orders straight into {@code sales_order}/{@code
 * sales_order_line} so each test states exactly the sales history it depends on.
 */
@Testcontainers
class BestSellersSortIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService service;
  static ObjectStorage storage;
  static final ObjectMapper JSON = ObjectMapperProvider.build();

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

    service =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CollectionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontCrawlRepositoryFactoryImpl(),
            storage,
            // Read-only tests never call checkout(), so the placement engine is unused here.
            null,
            null);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE sales_order_line, sales_order, product_listing_image, product_listing_category,"
            + " product_listing, category, product, org RESTART IDENTITY CASCADE");
  }

  // ───────── 1 · ranking by units sold, never-sold listing last ─────────

  @Test
  void ranksByUnitsSold_neverSoldListingIsLastNotHidden() {
    Seed s = seed("acme");
    // Slugs deliberately sort the OPPOSITE way alphabetically, so a passing assertion can only come
    // from the sales aggregate — never from the slug tie-break leaking through as the real order.
    UUID five = listing(s, "zeta-five", "Five", "x", "10.00");
    UUID two = listing(s, "mid-two", "Two", "y", "20.00");
    listing(s, "alpha-zero", "Zero", "z", "30.00");

    // Mixed money-committed statuses, and units split across orders — the aggregate must SUM.
    sale(s, five, 3, OrderStatus.PAID, daysAgo(1));
    sale(s, five, 2, OrderStatus.FULFILLED, daysAgo(2));
    sale(s, two, 2, OrderStatus.CLOSED, daysAgo(3));

    ListingPage p = bestSelling(s);
    assertEquals(List.of("zeta-five", "mid-two", "alpha-zero"), slugs(p));
    // The sort ranks — it never filters. All three listings are still on the page.
    assertEquals(3, p.total());
  }

  @Test
  void placedAtIsNull_fallsBackToCreatedAt() {
    Seed s = seed("acme");
    UUID sold = listing(s, "zeta-sold", "Sold", "x", "10.00");
    listing(s, "alpha-unsold", "Unsold", "y", "20.00");
    // No placed_at — created_at (defaulted to now()) is the sale timestamp, per the reporting
    // slice's COALESCE(placed_at, created_at) window semantics.
    sale(s, sold, 4, OrderStatus.PAID, null);

    assertEquals(List.of("zeta-sold", "alpha-unsold"), slugs(bestSelling(s)));
  }

  // ───────── 2 · status discipline: only money-committed orders are sales ─────────

  @Test
  void cancelledAndPendingPaymentOrders_doNotCount() {
    Seed s = seed("acme");
    UUID real = listing(s, "zeta-real", "Real", "x", "10.00");
    UUID phantom = listing(s, "alpha-phantom", "Phantom", "y", "20.00");

    sale(s, real, 1, OrderStatus.PAID, daysAgo(1));
    // Ten units that are not money: one order never paid, one cancelled, one expired.
    sale(s, phantom, 4, OrderStatus.PENDING_PAYMENT, daysAgo(1));
    sale(s, phantom, 3, OrderStatus.CANCELLED, daysAgo(1));
    sale(s, phantom, 3, OrderStatus.EXPIRED, daysAgo(1));

    // One real unit outranks ten uncommitted ones.
    assertEquals(List.of("zeta-real", "alpha-phantom"), slugs(bestSelling(s)));
    // And the phantom seller is not a seller at all.
    assertEquals(Set.of("zeta-real"), slugSet(bestSellingSold(s)));
  }

  // ───────── 3 · window discipline: the rolling 30 days ─────────

  @Test
  void salesOlderThanTheWindow_doNotCount() {
    Seed s = seed("acme");
    UUID recent = listing(s, "zeta-recent", "Recent", "x", "10.00");
    UUID stale = listing(s, "alpha-stale", "Stale", "y", "20.00");

    sale(s, recent, 1, OrderStatus.PAID, daysAgo(29));
    // A blockbuster that stopped selling 31 days ago — outside the window, so it ranks as unsold.
    sale(s, stale, 50, OrderStatus.PAID, daysAgo(31));

    assertEquals(List.of("zeta-recent", "alpha-stale"), slugs(bestSelling(s)));
    assertEquals(Set.of("zeta-recent"), slugSet(bestSellingSold(s)));
  }

  // ───────── 4 · the ranking is in SQL, so it survives paging ─────────

  @Test
  void rankingSurvivesOffsetLimitPaging() {
    Seed s = seed("acme");
    UUID five = listing(s, "zeta-five", "Five", "x", "10.00");
    UUID two = listing(s, "mid-two", "Two", "y", "20.00");
    listing(s, "alpha-zero", "Zero", "z", "30.00");
    sale(s, five, 5, OrderStatus.PAID, daysAgo(1));
    sale(s, two, 2, OrderStatus.PAID, daysAgo(1));

    ListingPage first = page(s, 0, 2);
    ListingPage second = page(s, 1, 2);
    assertEquals(List.of("zeta-five", "mid-two"), slugs(first));
    assertEquals(List.of("alpha-zero"), slugs(second));
    // No overlap, no omission, and total is the full set on every page.
    assertEquals(3, first.total());
    assertEquals(3, second.total());
  }

  // ───────── 5 · equal units fall to the standing slug ASC tie-break ─────────

  @Test
  void equalUnits_tieBreakOnSlugAsc() {
    Seed s = seed("acme");
    UUID bravo = listing(s, "bravo", "B", "x", "10.00");
    UUID alpha = listing(s, "alpha", "A", "y", "20.00");
    listing(s, "charlie", "C", "z", "30.00");
    sale(s, bravo, 3, OrderStatus.PAID, daysAgo(1));
    sale(s, alpha, 3, OrderStatus.PAID, daysAgo(2));

    assertEquals(List.of("alpha", "bravo", "charlie"), slugs(bestSelling(s)));

    // The tie-break is what makes paging deterministic — walk it one row at a time.
    List<String> paged = new java.util.ArrayList<>();
    for (int i = 0; i < 3; i++) {
      paged.add(page(s, i, 1).items().get(0).slug());
    }
    assertEquals(List.of("alpha", "bravo", "charlie"), paged);
  }

  // ───────── 6 · sold=true — the strip's honesty guard ─────────

  @Test
  void soldTrue_excludesNeverSoldListings() {
    Seed s = seed("acme");
    UUID five = listing(s, "zeta-five", "Five", "x", "10.00");
    UUID two = listing(s, "mid-two", "Two", "y", "20.00");
    listing(s, "alpha-zero", "Zero", "z", "30.00");
    sale(s, five, 5, OrderStatus.PAID, daysAgo(1));
    sale(s, two, 2, OrderStatus.PAID, daysAgo(1));

    ListingPage p = bestSellingSold(s);
    assertEquals(List.of("zeta-five", "mid-two"), slugs(p));
    // total must agree with the rows — the count applies the same predicate.
    assertEquals(2, p.total());
  }

  @Test
  void soldTrue_onAStoreWithNoSales_isAnEmptyPage_notAFabricatedList() {
    Seed s = seed("acme");
    listing(s, "one", "A", "x", "10.00");
    listing(s, "two", "B", "y", "20.00");

    ListingPage p = bestSellingSold(s);
    assertEquals(0, p.total());
    assertTrue(p.items().isEmpty());
    // …while the sort alone still shows the whole catalog (a sort must never shrink "N results").
    assertEquals(2, bestSelling(s).total());
  }

  @Test
  void soldTrue_composesWithOtherFiltersAndSorts() {
    Seed s = seed("acme");
    UUID cams = category(s.orgId, "Cameras", "cams");
    UUID dearSeller = listing(s, "gopro-max", "GoPro Max", "action cam", "500.00");
    UUID cheapSeller = listing(s, "gopro-hero", "GoPro Hero", "action cam", "150.00");
    UUID unsoldCam = listing(s, "gopro-wip", "GoPro Mini", "action cam", "200.00");
    UUID soldTripod = listing(s, "tripod", "Tripod", "sturdy", "120.00");
    categorize(dearSeller, cams);
    categorize(cheapSeller, cams);
    categorize(unsoldCam, cams);
    sale(s, dearSeller, 1, OrderStatus.PAID, daysAgo(1));
    sale(s, cheapSeller, 9, OrderStatus.PAID, daysAgo(1));
    sale(s, soldTripod, 4, OrderStatus.PAID, daysAgo(1));

    // sold AND category — the tripod sells but is out of category; the mini is in it but unsold.
    assertEquals(
        Set.of("gopro-max", "gopro-hero"),
        slugSet(
            service.listPublished(
                s.slug, "cams", null, null, null, null, null, "true", null, 0, 20)));
    // sold composes under a non-best_selling sort too (the predicate is independent of the order).
    assertEquals(
        List.of("tripod", "gopro-hero", "gopro-max"),
        slugs(
            service.listPublished(
                s.slug, null, null, null, null, "price_asc", null, "true", null, 0, 20)));
    // …and with best_selling + a price band.
    assertEquals(
        List.of("gopro-hero", "tripod"),
        slugs(
            service.listPublished(
                s.slug, null, null, "100", "300", "best_selling", null, "true", null, 0, 20)));
  }

  @Test
  void soldOtherThanTrue_is400_neverSilentlyCoerced() {
    Seed s = seed("acme");
    listing(s, "one", "A", "x", "10.00");

    for (String bad : List.of("yes", "1", "false", "TRUE")) {
      ValidationException e =
          assertThrows(
              ValidationException.class,
              () ->
                  service.listPublished(
                      s.slug, null, null, null, null, null, null, bad, null, 0, 20),
              "sold=" + bad);
      assertTrue(e.getMessage().contains("sold"), e.getMessage());
    }
  }

  // ───────── 7 · no leak: whitelisted shape, DRAFT never surfaces ─────────

  @Test
  void wellSellingDraftListing_neverAppears() {
    Seed s = seed("acme");
    UUID published = listing(s, "zeta-live", "Live", "x", "10.00");
    UUID draft = insert(s, "alpha-wip", "Wip", "y", "20.00", ListingStatus.DRAFT, null);
    sale(s, published, 1, OrderStatus.PAID, daysAgo(1));
    // The DRAFT listing's product is the store's best seller — status still wins.
    sale(s, draft, 99, OrderStatus.PAID, daysAgo(1));

    assertEquals(List.of("zeta-live"), slugs(bestSelling(s)));
    assertEquals(List.of("zeta-live"), slugs(bestSellingSold(s)));
  }

  @Test
  void rankedRowsCarryNoSoldCount_onlyTheWhitelistedFields() throws Exception {
    Seed s = seed("acme");
    UUID sold = listing(s, "gopro-live", "GoPro live", "action cam", "150.00");
    sale(s, sold, 7, OrderStatus.PAID, daysAgo(1));

    ListingPage p = bestSellingSold(s);
    List<PublicListingResponse> data = p.items().stream().map(PublicListingResponse::from).toList();
    String json = JSON.writeValueAsString(new PageResponse<>(data, p.total(), p.page(), p.size()));

    // The ordering is the fact — the number behind it stays server-side.
    for (String forbidden :
        List.of("sold", "units", "quantity", "product_id", "\"id\"", "status", "created_at")) {
      assertFalse(json.contains(forbidden), "leaked " + forbidden + " in " + json);
    }
    assertTrue(json.contains("gopro-live"), json);
  }

  // ───────── 8 · the 400 surface still names every valid sort ─────────

  @Test
  void unknownSort_is400_andTheMessageNamesBestSelling() {
    Seed s = seed("acme");
    listing(s, "one", "A", "x", "10.00");

    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                service.listPublished(
                    s.slug, null, null, null, null, "bestselling", null, null, null, 0, 20));
    assertTrue(e.getMessage().contains("best_selling"), e.getMessage());
    assertTrue(e.getMessage().contains("newest"), e.getMessage());
  }

  // ───────── reads under test ─────────

  private ListingPage bestSelling(Seed s) {
    return page(s, 0, 20);
  }

  private ListingPage page(Seed s, int page, int size) {
    return service.listPublished(
        s.slug, null, null, null, null, "best_selling", null, null, null, page, size);
  }

  private ListingPage bestSellingSold(Seed s) {
    return service.listPublished(
        s.slug, null, null, null, null, "best_selling", null, "true", null, 0, 20);
  }

  // ───────── seeding helpers ─────────

  private record Seed(UUID orgId, String slug) {}

  private Seed seed(String name) {
    UUID orgId = UUID.randomUUID();
    String slug = name + "-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, name)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    return new Seed(orgId, slug);
  }

  private static OffsetDateTime daysAgo(int days) {
    return OffsetDateTime.now().minusDays(days);
  }

  /**
   * One order carrying {@code quantity} units of the listing's product, in {@code status}, stamped
   * at {@code placedAt} (null → the row's {@code created_at} default stands in, exercising the
   * COALESCE arm). The order is deliberately hand-seeded rather than placed through {@code
   * SalesOrderService}: the aggregate under test reads only these two tables, and stating the sales
   * history literally keeps each case readable.
   */
  private void sale(
      Seed s, UUID listingId, int quantity, OrderStatus status, OffsetDateTime placedAt) {
    UUID productId = listingRepo().findById(s.orgId, listingId).orElseThrow().getProductId();
    UUID orderId = UUID.randomUUID();
    var insert =
        dsl.insertInto(SALES_ORDER)
            .set(SALES_ORDER.ID, orderId)
            .set(SALES_ORDER.ORG_ID, s.orgId)
            .set(SALES_ORDER.ORDER_NUMBER, "SO-" + orderId)
            .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
            .set(SALES_ORDER.STATUS, status)
            .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("100.00"));
    if (placedAt != null) {
      insert = insert.set(SALES_ORDER.PLACED_AT, placedAt).set(SALES_ORDER.CREATED_AT, placedAt);
    }
    insert.execute();

    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, UUID.randomUUID())
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, productId)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, quantity)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(
            SALES_ORDER_LINE.LINE_SUBTOTAL,
            new BigDecimal("10.00").multiply(new BigDecimal(quantity)))
        .set(
            SALES_ORDER_LINE.LINE_TOTAL, new BigDecimal("10.00").multiply(new BigDecimal(quantity)))
        .execute();
  }

  private UUID product(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, "P-" + id)
        .set(PRODUCT.SKU, "SKU-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private UUID category(UUID orgId, String name, String slug) {
    Category c = new Category();
    c.setOrgId(orgId);
    c.setName(name);
    c.setSlug(slug);
    return new CategoryRepositoryFactoryImpl().create(dsl).insert(c).getId();
  }

  private void categorize(UUID listingId, UUID categoryId) {
    listingRepo().replaceCategories(listingId, Set.of(categoryId));
  }

  private UUID listing(Seed s, String slug, String title, String copy, String price) {
    return insert(s, slug, title, copy, price, ListingStatus.PUBLISHED, OffsetDateTime.now());
  }

  private UUID insert(
      Seed s,
      String slug,
      String title,
      String copy,
      String price,
      ListingStatus status,
      OffsetDateTime publishedAt) {
    ProductListing l = new ProductListing();
    l.setOrgId(s.orgId);
    l.setProductId(product(s.orgId));
    l.setTitle(title);
    l.setMarketingCopy(copy);
    l.setSlug(slug);
    l.setSalesPrice(new BigDecimal(price));
    l.setStatus(status);
    l.setPublishedAt(publishedAt);
    UUID listingId = listingRepo().insert(l).getId();
    for (String lang : new String[] {"ar", "en"}) {
      dsl.insertInto(PRODUCT_LISTING_TRANSLATION)
          .set(PRODUCT_LISTING_TRANSLATION.LISTING_ID, listingId)
          .set(PRODUCT_LISTING_TRANSLATION.LANGUAGE, lang)
          .set(PRODUCT_LISTING_TRANSLATION.TITLE, title)
          .set(PRODUCT_LISTING_TRANSLATION.MARKETING_COPY, copy)
          .execute();
    }
    return listingId;
  }

  private ProductListingRepository listingRepo() {
    return new ProductListingRepositoryFactoryImpl().create(dsl);
  }

  private static List<String> slugs(ListingPage p) {
    return p.items().stream().map(ListingView::slug).toList();
  }

  private static Set<String> slugSet(ListingPage p) {
    return p.items().stream().map(ListingView::slug).collect(java.util.stream.Collectors.toSet());
  }
}
