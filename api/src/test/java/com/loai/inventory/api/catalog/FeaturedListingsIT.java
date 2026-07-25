package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.ProductListingResponse;
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
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductListingService.ListingView;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.ListingPage;
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
import java.util.ArrayList;
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
 * Featured listings — merchant curation for the storefront home ({@code
 * stories/storefront_featured_listings.md}, slice C3). Covers the admin set-replace matrix (order
 * persisted 0..n-1, omitted cleared, replay idempotent, the >12 / duplicate / foreign-id 400s with
 * nothing applied), the admin ordered read across all statuses, the public {@code ?featured=true}
 * read (PUBLISHED-only, curated order, unpublish drops / re-publish restores, empty → 200), the
 * composition rules ({@code &sort=} overrides, {@code &category=} intersects, {@code featured=nope}
 * → 400), and the no-leak invariants (no {@code featured_sort} in either plane's JSON; the B3
 * default-sort regression rider). Drives {@link ProductListingService} (admin) and {@link
 * StorefrontService} (public) directly — the house pattern.
 */
@Testcontainers
class FeaturedListingsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ProductListingService admin;
  static StorefrontService storefront;
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

    admin =
        new ProductListingService(
            dsl,
            new ProductListingRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl(),
            storage);
    storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
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
  void fresh() {
    dsl.execute(
        "TRUNCATE product_listing_image, product_listing_category, product_listing, category,"
            + " product, org RESTART IDENTITY CASCADE");
  }

  // ───────── AC1: set-replace atomicity ─────────

  @Test
  void setReplace_persistsOrder_clearsOmitted_andIsIdempotent() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "A", "x", "10.00");
    UUID b = listing(s, "b", "B", "y", "20.00");
    UUID c = listing(s, "c", "C", "z", "30.00");

    // Order persisted as given, 0..n-1 (not insertion or price order).
    admin.setFeatured(s.orgId, List.of(b, a, c));
    assertEquals(List.of("b", "a", "c"), adminSlugs(s.orgId));

    // Replaying the same list is idempotent.
    admin.setFeatured(s.orgId, List.of(b, a, c));
    assertEquals(List.of("b", "a", "c"), adminSlugs(s.orgId));

    // A narrower set clears the omitted ids (b, c drop to NULL).
    admin.setFeatured(s.orgId, List.of(a));
    assertEquals(List.of("a"), adminSlugs(s.orgId));

    // The empty set clears everything.
    admin.setFeatured(s.orgId, List.of());
    assertTrue(adminSlugs(s.orgId).isEmpty());
  }

  @Test
  void setReplace_rejectsOverCap_duplicate_andForeignIds_applyingNothing() {
    Seed s = seed("acme");
    List<UUID> thirteen = new ArrayList<>();
    for (int i = 0; i < 13; i++) {
      thirteen.add(listing(s, "L" + i, "T" + i, "c", "10.00"));
    }
    UUID a = thirteen.get(0);
    UUID b = thirteen.get(1);

    // A good baseline we can prove is left untouched by each rejected write.
    admin.setFeatured(s.orgId, List.of(a, b));
    assertEquals(List.of("L0", "L1"), adminSlugs(s.orgId));

    // > 12 → 400.
    ValidationException tooMany =
        assertThrows(ValidationException.class, () -> admin.setFeatured(s.orgId, thirteen));
    assertTrue(tooMany.getMessage().contains("12"), tooMany.getMessage());

    // Duplicate id → 400.
    ValidationException dup =
        assertThrows(ValidationException.class, () -> admin.setFeatured(s.orgId, List.of(a, a)));
    assertTrue(dup.getMessage().toLowerCase().contains("duplicate"), dup.getMessage());

    // Foreign id (another org's listing) → 400.
    Seed other = seed("other");
    UUID foreign = listing(other, "foreign", "F", "c", "10.00");
    ValidationException alien =
        assertThrows(
            ValidationException.class, () -> admin.setFeatured(s.orgId, List.of(a, foreign)));
    assertTrue(alien.getMessage().contains("this org"), alien.getMessage());

    // Unknown (never-existed) id → 400.
    assertThrows(
        ValidationException.class, () -> admin.setFeatured(s.orgId, List.of(a, UUID.randomUUID())));

    // Nothing applied by any rejected write — the baseline stands.
    assertEquals(List.of("L0", "L1"), adminSlugs(s.orgId));
  }

  // ───────── AC2: admin read across all statuses ─────────

  @Test
  void adminRead_returnsOrderedRows_inEveryStatus() {
    Seed s = seed("acme");
    UUID pub = listing(s, "pub", "Pub", "c", "10.00");
    UUID draft = listingWithStatus(s, "draft", "Draft", "c", "20.00", ListingStatus.DRAFT);
    UUID arch = listingWithStatus(s, "arch", "Arch", "c", "30.00", ListingStatus.ARCHIVED);

    admin.setFeatured(s.orgId, List.of(draft, pub, arch));

    List<ListingView> rows = admin.getFeatured(s.orgId);
    assertEquals(
        List.of("draft", "pub", "arch"), rows.stream().map(v -> v.listing().getSlug()).toList());
    // The DRAFT and ARCHIVED rows are present with their real status (the picker badges them).
    assertEquals("DRAFT", rows.get(0).listing().getStatus().name());
    assertEquals("PUBLISHED", rows.get(1).listing().getStatus().name());
    assertEquals("ARCHIVED", rows.get(2).listing().getStatus().name());
  }

  // ───────── AC3: public read — PUBLISHED-only, curated order, drop/restore, empty ─────────

  @Test
  void publicRead_returnsFeaturedPublished_inCuratedOrder() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "A", "x", "30.00");
    UUID b = listing(s, "b", "B", "y", "10.00");
    UUID c = listing(s, "c", "C", "z", "20.00");
    listing(s, "not-featured", "N", "n", "5.00"); // published but never curated → absent

    admin.setFeatured(s.orgId, List.of(c, a, b));
    assertEquals(List.of("c", "a", "b"), publicFeaturedSlugs(s.slug));
  }

  @Test
  void publicRead_unpublishDropsOne_republishRestoresOrderIntact_adminStillShowsIt() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "A", "x", "10.00");
    UUID b = listing(s, "b", "B", "y", "20.00");
    UUID c = listing(s, "c", "C", "z", "30.00");
    admin.setFeatured(s.orgId, List.of(a, b, c));

    // Unpublish the middle one → it silently drops from the public strip, order otherwise intact.
    admin.unpublish(s.orgId, b);
    assertEquals(List.of("a", "c"), publicFeaturedSlugs(s.slug));
    // ...but the admin curation still lists it (badged not-published).
    assertEquals(List.of("a", "b", "c"), adminSlugs(s.orgId));

    // Re-publish → it returns in its original slot (featured_sort survived the unpublish).
    admin.publish(s.orgId, b);
    assertEquals(List.of("a", "b", "c"), publicFeaturedSlugs(s.slug));
  }

  @Test
  void publicRead_uncuratedOrg_isEmptyPage_not404() {
    Seed s = seed("acme");
    listing(s, "a", "A", "x", "10.00"); // published, but nothing featured
    ListingPage p = storefront.listPublished(s.slug, null, null, null, null, null, "true", 0, 20);
    assertEquals(0, p.total());
    assertTrue(p.items().isEmpty());
  }

  // ───────── AC4: composition ─────────

  @Test
  void composition_explicitSortOverridesCuratedOrder() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "A", "x", "30.00");
    UUID b = listing(s, "b", "B", "y", "10.00");
    UUID c = listing(s, "c", "C", "z", "20.00");
    admin.setFeatured(s.orgId, List.of(a, b, c)); // curated order a,b,c

    // ?featured=true&sort=price_asc re-sorts by price, not featured_sort.
    ListingPage p =
        storefront.listPublished(s.slug, null, null, null, null, "price_asc", "true", 0, 20);
    assertEquals(List.of("b", "c", "a"), slugs(p));
  }

  @Test
  void composition_categoryIntersectsFeatured() {
    Seed s = seed("acme");
    UUID cams = category(s.orgId, "Cameras", "cams");
    UUID a = listing(s, "a", "A", "x", "10.00");
    UUID b = listing(s, "b", "B", "y", "20.00");
    categorize(a, cams);
    // b is featured but NOT in cams; a is featured AND in cams.
    admin.setFeatured(s.orgId, List.of(b, a));

    ListingPage p = storefront.listPublished(s.slug, "cams", null, null, null, null, "true", 0, 20);
    assertEquals(List.of("a"), slugs(p));
  }

  @Test
  void composition_featuredBadValue_is400() {
    Seed s = seed("acme");
    listing(s, "a", "A", "x", "10.00");
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> storefront.listPublished(s.slug, null, null, null, null, null, "nope", 0, 20));
    assertTrue(e.getMessage().contains("featured"), e.getMessage());
  }

  @Test
  void servlet_featuredTrue_setsCacheHeader_andBadValue_is400() throws Exception {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "A", "x", "10.00");
    admin.setFeatured(s.orgId, List.of(a));

    HttpServletResponse ok = driveServlet("/" + s.slug + "/listings", "featured", "true");
    verify(ok).setStatus(200);
    verify(ok).setHeader("Cache-Control", "public, max-age=60");

    HttpServletResponse bad = driveServlet("/" + s.slug + "/listings", "featured", "1");
    verify(bad).setStatus(400);
  }

  // ───────── AC5: no-leak + B3 regression rider ─────────

  @Test
  void neitherPlaneLeaksFeaturedSort_inJson() throws Exception {
    Seed s = seed("acme");
    UUID a = listing(s, "gopro-live", "GoPro live", "action cam", "150.00");
    admin.setFeatured(s.orgId, List.of(a));

    // Public row: whitelisted only — no featured_sort/featured, no internal ids.
    ListingPage p = storefront.listPublished(s.slug, null, null, null, null, null, "true", 0, 20);
    String publicJson =
        JSON.writeValueAsString(
            p.items().stream()
                .map(com.loai.inventory.api.dto.PublicListingResponse::from)
                .toList());
    for (String forbidden : List.of("featured_sort", "\"featured\"", "product_id", "\"id\"")) {
      assertFalse(
          publicJson.contains(forbidden), "public leaked '" + forbidden + "': " + publicJson);
    }

    // Admin row carries id/status (it's the admin plane) but never featured_sort.
    String adminJson =
        JSON.writeValueAsString(
            admin.getFeatured(s.orgId).stream().map(ProductListingResponse::fromView).toList());
    assertFalse(adminJson.contains("featured_sort"), adminJson);
    assertFalse(adminJson.contains("\"featured\""), adminJson);
  }

  @Test
  void b3Regression_plainReadIsUnaffected_byFeaturedDefaulting() {
    Seed s = seed("acme");
    OffsetDateTime t0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    UUID older = listingAt(s, "older", "A", "x", "10.00", t0);
    listingAt(s, "newer", "B", "y", "20.00", t0.plusDays(1));
    // Feature only the older one — a non-featured read must still return BOTH, newest-first.
    admin.setFeatured(s.orgId, List.of(older));

    ListingPage plain = storefront.listPublished(s.slug, null, null, null, null, null, null, 0, 20);
    assertEquals(2, plain.total());
    assertEquals(List.of("newer", "older"), slugs(plain)); // default = NEWEST, not FEATURED
  }

  // ───────── servlet harness ─────────

  private HttpServletResponse driveServlet(String pathInfo, String... params) throws Exception {
    PublicStorefrontServlet servlet = new PublicStorefrontServlet();
    inject(servlet, "service", storefront);
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
    return listingRepo().insert(l).getId();
  }

  private ProductListingRepository listingRepo() {
    return new ProductListingRepositoryFactoryImpl().create(dsl);
  }

  private List<String> adminSlugs(UUID orgId) {
    return admin.getFeatured(orgId).stream().map(v -> v.listing().getSlug()).toList();
  }

  private List<String> publicFeaturedSlugs(String orgSlug) {
    return slugs(storefront.listPublished(orgSlug, null, null, null, null, null, "true", 0, 20));
  }

  private static List<String> slugs(ListingPage p) {
    return p.items().stream().map(StorefrontService.ListingView::slug).toList();
  }
}
