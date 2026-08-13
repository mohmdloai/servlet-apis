package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.api.servlet.PublicStorefrontServlet;
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
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.ListingPage;
import com.loai.inventory.service.StorefrontService.ListingView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
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
 * Storefront search + filters ({@code stories/storefront_search_and_filters.md}, B3): {@code q}
 * substring over title/copy, inclusive price band, the three sort orders with a stable tie-break,
 * the 400 matrix (unknown sort, bad price, min &gt; max), and the no-leak invariants — a
 * DRAFT/ARCHIVED row never matches under any filter, and a serialized search row carries only the
 * whitelisted public fields. Drives {@link StorefrontService} directly (the house pattern), plus a
 * thin servlet-level slice for the {@code Cache-Control} header and the 400 surface.
 */
@Testcontainers
class StorefrontSearchIT {

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
        "TRUNCATE product_listing_image, product_listing_category, product_listing, category,"
            + " product, org RESTART IDENTITY CASCADE");
  }

  // ───────── q: substring over title AND copy, case-insensitive, trimmed ─────────

  @Test
  void q_matchesTitleAndMarketingCopy_caseInsensitively() {
    Seed s = seed("acme");
    listing(s, "in-title", "GoPro Hero3", "an action camera", "100.00");
    listing(s, "in-copy", "Action camera", "the classic gopro form factor", "150.00");
    listing(s, "neither", "Notebook", "ruled paper", "50.00");

    ListingPage page = search(s, null, "gopro", null, null, null);
    assertEquals(2, page.total());
    assertEquals(Set.of("in-title", "in-copy"), slugSet(page));

    // Different-cased query still matches (ILIKE semantics).
    assertEquals(2, search(s, null, "GOPRO", null, null, null).total());
  }

  @Test
  void q_blankOrEmpty_isIgnored_notAnEmptyPage() {
    Seed s = seed("acme");
    listing(s, "one", "A", "x", "10.00");
    listing(s, "two", "B", "y", "20.00");

    long all = search(s, null, null, null, null, null).total();
    assertEquals(2, all);
    assertEquals(all, search(s, null, "", null, null, null).total());
    assertEquals(all, search(s, null, "   ", null, null, null).total());
  }

  // ───────── price band: inclusive both ends ─────────

  @Test
  void priceBand_isInclusive() {
    Seed s = seed("acme");
    listing(s, "cheap", "A", "x", "100.00");
    listing(s, "mid", "B", "y", "150.00");
    listing(s, "dear", "C", "z", "200.00");

    assertEquals(
        Set.of("cheap", "mid", "dear"), slugSet(search(s, null, null, "100", "200", null)));
    assertEquals(Set.of("mid", "dear"), slugSet(search(s, null, null, "150", null, null)));
    assertEquals(Set.of("cheap", "mid"), slugSet(search(s, null, null, null, "150", null)));
  }

  // ───────── sort: each order, default, stable tie-break across pages ─────────

  @Test
  void sort_priceAscAndDesc_orderBySalesPrice() {
    Seed s = seed("acme");
    listing(s, "mid", "B", "y", "150.00");
    listing(s, "dear", "C", "z", "200.00");
    listing(s, "cheap", "A", "x", "100.00");

    assertEquals(
        List.of("cheap", "mid", "dear"), slugs(search(s, null, null, null, null, "price_asc")));
    assertEquals(
        List.of("dear", "mid", "cheap"), slugs(search(s, null, null, null, null, "price_desc")));
  }

  @Test
  void sort_newestAndDefault_orderByPublishedAtDesc() {
    Seed s = seed("acme");
    OffsetDateTime t0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    listingAt(s, "oldest", "A", "x", "10.00", t0);
    listingAt(s, "newest", "B", "y", "20.00", t0.plusDays(2));
    listingAt(s, "middle", "C", "z", "30.00", t0.plusDays(1));

    assertEquals(
        List.of("newest", "middle", "oldest"), slugs(search(s, null, null, null, null, "newest")));
    // Absent sort = the same default.
    assertEquals(
        List.of("newest", "middle", "oldest"), slugs(search(s, null, null, null, null, null)));
  }

  @Test
  void sort_tieBreak_isStableAcrossPages() {
    Seed s = seed("acme");
    OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    // Four listings published in the SAME instant at the SAME price — pure tie-break territory.
    for (String slug : List.of("delta", "alpha", "charlie", "bravo")) {
      listingAt(s, slug, slug + " title", "copy", "99.00", t);
    }

    for (String sort : List.of("newest", "price_asc", "price_desc")) {
      List<String> paged = new java.util.ArrayList<>();
      for (int page = 0; page < 4; page++) {
        ListingPage p = service.listPublished(s.slug, null, null, null, null, sort, null, page, 1);
        assertEquals(4, p.total());
        assertEquals(1, p.items().size(), "sort=" + sort + " page=" + page);
        paged.add(p.items().get(0).slug());
      }
      // Slug ASC tie-break: deterministic, no row repeated or dropped across page boundaries.
      assertEquals(List.of("alpha", "bravo", "charlie", "delta"), paged, "sort=" + sort);
    }
  }

  // ───────── combined filters AND together ─────────

  @Test
  void combinedFilters_allAnd_totalMatchesPage() {
    Seed s = seed("acme");
    UUID cams = category(s.orgId, "Cameras", "cams");
    // In category, matches term, in band → the one expected hit.
    UUID hit = listing(s, "gopro-hero", "GoPro Hero", "action cam", "150.00");
    categorize(hit, cams);
    // In category + term but OUT of band.
    UUID dear = listing(s, "gopro-max", "GoPro Max", "action cam", "500.00");
    categorize(dear, cams);
    // In category + band but no term.
    UUID plain = listing(s, "tripod", "Tripod", "sturdy", "120.00");
    categorize(plain, cams);
    // Term + band but OTHER category.
    UUID other = listing(s, "gopro-case", "GoPro case", "fits hero", "130.00");
    categorize(other, category(s.orgId, "Accessories", "acc"));
    // Term + band + category but DRAFT — must never appear.
    UUID draft =
        listingWithStatus(
            s, "gopro-draft", "GoPro draft", "action cam", "140.00", ListingStatus.DRAFT);
    categorize(draft, cams);

    ListingPage p =
        service.listPublished(s.slug, "cams", "gopro", "100", "200", "price_asc", null, 0, 10);
    assertEquals(1, p.total());
    assertEquals(List.of("gopro-hero"), slugs(p));
  }

  // ───────── the 400 matrix ─────────

  @Test
  void unknownSort_is400_neverSilentDefault() {
    Seed s = seed("acme");
    listing(s, "one", "A", "x", "10.00");
    ValidationException e =
        assertThrows(
            ValidationException.class, () -> search(s, null, null, null, null, "cheapest"));
    assertTrue(e.getMessage().contains("sort"), e.getMessage());
  }

  @Test
  void badPrice_is400_withCauseNamingMessage() {
    Seed s = seed("acme");
    listing(s, "one", "A", "x", "10.00");

    ValidationException nonNumeric =
        assertThrows(ValidationException.class, () -> search(s, null, null, "abc", null, null));
    assertTrue(nonNumeric.getMessage().contains("min_price"), nonNumeric.getMessage());

    ValidationException negative =
        assertThrows(ValidationException.class, () -> search(s, null, null, null, "-1", null));
    assertTrue(negative.getMessage().contains("max_price"), negative.getMessage());

    ValidationException inverted =
        assertThrows(ValidationException.class, () -> search(s, null, null, "200", "100", null));
    assertTrue(
        inverted.getMessage().contains("min_price must not exceed max_price"),
        inverted.getMessage());
  }

  @Test
  void wellFormedNoMatchQuery_isEmptyPage_not400() {
    Seed s = seed("acme");
    listing(s, "one", "A", "x", "10.00");
    ListingPage p = search(s, null, "zzz-no-such-term", "5", "9999", "price_desc");
    assertEquals(0, p.total());
    assertTrue(p.items().isEmpty());
  }

  // ───────── no-leak: DRAFT/ARCHIVED never match; whitelisted JSON only ─────────

  @Test
  void draftAndArchived_neverAppear_underAnyFilterCombination() {
    Seed s = seed("acme");
    UUID cams = category(s.orgId, "Cameras", "cams");
    UUID published = listing(s, "gopro-live", "GoPro live", "action cam", "150.00");
    categorize(published, cams);
    // A DRAFT and an ARCHIVED listing that match the term AND sit inside the band.
    UUID draft =
        listingWithStatus(s, "gopro-wip", "GoPro wip", "action cam", "150.00", ListingStatus.DRAFT);
    categorize(draft, cams);
    UUID archived =
        listingWithStatus(
            s, "gopro-old", "GoPro old", "action cam", "150.00", ListingStatus.ARCHIVED);
    categorize(archived, cams);

    List<ListingPage> combos =
        List.of(
            search(s, null, "gopro", null, null, null),
            search(s, null, null, "100", "200", null),
            search(s, null, "gopro", "100", "200", "price_asc"),
            search(s, "cams", "gopro", "100", "200", "price_desc"),
            search(s, null, null, null, null, "newest"));
    for (ListingPage p : combos) {
      assertEquals(1, p.total());
      assertEquals(List.of("gopro-live"), slugs(p));
    }
  }

  @Test
  void searchRow_serializesOnlyWhitelistedFields() throws Exception {
    Seed s = seed("acme");
    listing(s, "gopro-live", "GoPro live", "action cam", "150.00");

    ListingPage p = search(s, null, "gopro", "100", "200", "price_asc");
    List<PublicListingResponse> data = p.items().stream().map(PublicListingResponse::from).toList();
    String json = JSON.writeValueAsString(new PageResponse<>(data, p.total(), p.page(), p.size()));

    for (String forbidden :
        List.of(
            "product_id",
            "\"id\"",
            "status",
            "published_at",
            "created_at",
            "updated_at",
            "org_id",
            "object_key")) {
      assertFalse(json.contains(forbidden), "leaked '" + forbidden + "' in: " + json);
    }
    assertTrue(json.contains("\"slug\":\"gopro-live\""), json);
    assertTrue(json.contains("\"sales_price\""), json);
  }

  // ───────── servlet slice: cache header + the 400 surface over HTTP semantics ─────────

  @Test
  void successfulSearch_setsPublicMaxAge60_cacheHeader() throws Exception {
    Seed s = seed("acme");
    listing(s, "gopro-live", "GoPro live", "action cam", "150.00");

    HttpServletResponse resp =
        driveServlet("/" + s.slug + "/listings", "q", "gopro", "sort", "price_asc");
    verify(resp).setStatus(200);
    verify(resp).setHeader("Cache-Control", "public, max-age=60");
  }

  @Test
  void unknownSort_is400_throughTheServlet() throws Exception {
    Seed s = seed("acme");
    listing(s, "gopro-live", "GoPro live", "action cam", "150.00");

    HttpServletResponse resp = driveServlet("/" + s.slug + "/listings", "sort", "cheapest");
    verify(resp).setStatus(400);
  }

  /**
   * Drive {@link PublicStorefrontServlet} directly with the real service + mapper
   * (reflection-injected — {@code init()} needs a servlet context we don't have) and mocked
   * request/response. Returns the response mock for header/status verification.
   */
  private HttpServletResponse driveServlet(String pathInfo, String... params) throws Exception {
    PublicStorefrontServlet servlet = new PublicStorefrontServlet();
    inject(servlet, "service", service);
    inject(servlet, "mapper", JSON);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(pathInfo);
    for (int i = 0; i < params.length; i += 2) {
      when(req.getParameter(params[i])).thenReturn(params[i + 1]);
    }

    HttpServletResponse resp = mock(HttpServletResponse.class);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(resp.getOutputStream())
        .thenReturn(
            new ServletOutputStream() {
              @Override
              public void write(int b) {
                body.write(b);
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setWriteListener(WriteListener listener) {}
            });

    servlet.service(req, resp);
    return resp;
  }

  private static void inject(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
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
    return listingAt(s, slug, title, copy, price, OffsetDateTime.now());
  }

  private UUID listingAt(
      Seed s, String slug, String title, String copy, String price, OffsetDateTime publishedAt) {
    return insert(s, slug, title, copy, price, ListingStatus.PUBLISHED, publishedAt);
  }

  private UUID listingWithStatus(
      Seed s, String slug, String title, String copy, String price, ListingStatus status) {
    return insert(
        s,
        slug,
        title,
        copy,
        price,
        status,
        status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null);
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
    // Search resolves against the per-language translation rows (L6 — the legacy
    // title/marketing_copy
    // columns and their search arm are gone). Seed both locales the same, so the query matches
    // under
    // whichever locale resolution picks.
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

  private ListingPage search(
      Seed s, String categorySlug, String q, String min, String max, String sort) {
    return service.listPublished(s.slug, categorySlug, q, min, max, sort, null, 0, 20);
  }

  private static List<String> slugs(ListingPage p) {
    return p.items().stream().map(ListingView::slug).toList();
  }

  private static Set<String> slugSet(ListingPage p) {
    return p.items().stream().map(ListingView::slug).collect(java.util.stream.Collectors.toSet());
  }
}
