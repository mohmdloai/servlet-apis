package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.CollectionResponse;
import com.loai.inventory.api.dto.PublicCollectionResponse;
import com.loai.inventory.api.servlet.PublicStorefrontServlet;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.Collection;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingTranslation;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CollectionRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgPaymobConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.CollectionService;
import com.loai.inventory.service.CollectionService.CollectionView;
import com.loai.inventory.service.ImagePresign;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.ListingPage;
import com.loai.inventory.service.StorefrontService.PublicCollectionView;
import com.loai.inventory.service.platform.OrgMilestoneService;
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
 * Named collections — roadmap item 8, {@code stories/storefront_collections.md} (V71). Covers the
 * six IT groups the slice names: the admin curation roundtrip (order persisted,
 * caps/duplicates/foreign ids rejected atomically), the public {@code ?collection={slug}} read
 * (curated default order, PUBLISHED-only, explicit sort overrides, composition with the rest of the
 * B3 grammar, unknown slug → empty 200), rail honesty (an all-draft collection is never advertised;
 * locale names resolve), lifecycle (listing delete cascades out, collection delete leaves listings,
 * slug rename moves the landing page), the no-leak/isolation invariants, and the featured
 * regression rider.
 *
 * <p>Drives {@link CollectionService} (admin) and {@link StorefrontService} (public) directly — the
 * house pattern, mirroring {@code FeaturedListingsIT}.
 */
@Testcontainers
class CollectionsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CollectionService admin;
  static ProductListingService listings;
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

    listings =
        new ProductListingService(
            dsl,
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl(),
            storage,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    admin =
        new CollectionService(
            dsl,
            new CollectionRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            listings,
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
            new CollectionRepositoryFactoryImpl(),
            new OrgWhatsAppConfigRepositoryFactoryImpl(),
            new OrgPaymobConfigRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontCrawlRepositoryFactoryImpl(),
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
        "TRUNCATE collection_listing, collection_translation, collection, product_listing_image,"
            + " product_listing_translation, product_listing_category, product_listing, category,"
            + " product, org RESTART IDENTITY CASCADE");
  }

  // group 1: admin roundtrip

  @Test
  void adminRoundtrip_createsCuratesReordersAndCounts() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "10.00");
    UUID b = listing(s, "b", "Beta", "20.00");
    UUID c = listing(s, "c", "Gamma", "30.00");

    Collection created =
        admin.create(s.orgId, "ramadan-picks", "مختارات رمضان", "Ramadan picks", 2);
    assertNotNull(created.getId());
    // The org default locale is 'ar', so the display scalar is the Arabic name.
    assertEquals("مختارات رمضان", created.getName());

    admin.setListings(s.orgId, created.getId(), List.of(b, a, c));
    assertEquals(List.of("b", "a", "c"), curatedSlugs(s.orgId, created.getId()));

    // The list row carries both names + the count, batch-loaded.
    List<CollectionView> all = admin.getAll(s.orgId);
    assertEquals(1, all.size());
    assertEquals(3L, all.get(0).listingCount());
    CollectionResponse row = CollectionResponse.from(all.get(0));
    assertEquals("مختارات رمضان", row.getNameAr());
    assertEquals("Ramadan picks", row.getNameEn());
    assertEquals(2, row.getSortOrder());

    // Re-PUT reordered → the new order, and replay is idempotent.
    admin.setListings(s.orgId, created.getId(), List.of(c, b, a));
    assertEquals(List.of("c", "b", "a"), curatedSlugs(s.orgId, created.getId()));
    admin.setListings(s.orgId, created.getId(), List.of(c, b, a));
    assertEquals(List.of("c", "b", "a"), curatedSlugs(s.orgId, created.getId()));

    // A narrower set drops the omitted ids; the empty set empties the collection.
    admin.setListings(s.orgId, created.getId(), List.of(a));
    assertEquals(List.of("a"), curatedSlugs(s.orgId, created.getId()));
    admin.setListings(s.orgId, created.getId(), List.of());
    assertTrue(curatedSlugs(s.orgId, created.getId()).isEmpty());
  }

  @Test
  void adminWrites_rejectCapsDuplicatesAndForeignIds_applyingNothing() {
    Seed s = seed("acme");
    UUID col = admin.create(s.orgId, "shelf", "رف", "Shelf", null).getId();
    UUID a = listing(s, "a", "Alpha", "10.00");
    UUID b = listing(s, "b", "Beta", "20.00");

    // A good baseline each rejected write must leave untouched.
    admin.setListings(s.orgId, col, List.of(a, b));
    assertEquals(List.of("a", "b"), curatedSlugs(s.orgId, col));

    // 101 listings → 400 (nothing applied).
    List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      tooMany.add(listing(s, "bulk-" + i, "Bulk " + i, "5.00"));
    }
    ValidationException over =
        assertThrows(ValidationException.class, () -> admin.setListings(s.orgId, col, tooMany));
    assertTrue(over.getMessage().contains("100"), over.getMessage());

    // Duplicate id → 400.
    ValidationException dup =
        assertThrows(
            ValidationException.class, () -> admin.setListings(s.orgId, col, List.of(a, a)));
    assertTrue(dup.getMessage().toLowerCase().contains("duplicate"), dup.getMessage());

    // Foreign listing (another org's) → 400; unknown id → 400.
    Seed other = seed("other");
    UUID foreign = listing(other, "foreign", "Foreign", "10.00");
    ValidationException alien =
        assertThrows(
            ValidationException.class, () -> admin.setListings(s.orgId, col, List.of(a, foreign)));
    assertTrue(alien.getMessage().contains("this org"), alien.getMessage());
    assertThrows(
        ValidationException.class,
        () -> admin.setListings(s.orgId, col, List.of(a, UUID.randomUUID())));

    // Nothing applied by any rejected write.
    assertEquals(List.of("a", "b"), curatedSlugs(s.orgId, col));

    // Duplicate slug → 409.
    ConflictException conflict =
        assertThrows(
            ConflictException.class, () -> admin.create(s.orgId, "shelf", "رف٢", "Shelf 2", null));
    assertTrue(conflict.getMessage().contains("shelf"), conflict.getMessage());

    // The 31st collection → 400 (one already exists, so 29 more fill the cap of 30).
    for (int i = 0; i < 29; i++) {
      admin.create(s.orgId, "shelf-" + i, "رف " + i, "Shelf " + i, null);
    }
    ValidationException capped =
        assertThrows(
            ValidationException.class,
            () -> admin.create(s.orgId, "one-too-many", "زائد", "X", null));
    assertTrue(capped.getMessage().contains("30"), capped.getMessage());
  }

  @Test
  void adminWrites_rejectBadSlugAndMissingDefaultLocaleName() {
    Seed s = seed("acme");
    // Slug shape: it is a public URL segment, so the org-slug discipline applies.
    for (String bad : List.of("", " ", "-leading", "trailing-", "Has Space", "UPPER!")) {
      assertThrows(
          ValidationException.class, () -> admin.create(s.orgId, bad, "اسم", "Name", null), bad);
    }
    // The org's default locale is 'ar' — an English-only name is a 400 naming the locale.
    ValidationException missing =
        assertThrows(
            ValidationException.class, () -> admin.create(s.orgId, "en-only", null, "Only", null));
    assertTrue(missing.getMessage().contains("ar"), missing.getMessage());
    // sort_order out of range → 400.
    assertThrows(ValidationException.class, () -> admin.create(s.orgId, "neg", "اسم", "Name", -1));
  }

  // group 2: public read

  @Test
  void publicRead_servesCuratedOrder_publishedOnly() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "30.00");
    UUID b = listing(s, "b", "Beta", "10.00");
    UUID c = listing(s, "c", "Gamma", "20.00");
    listing(s, "outside", "Outside", "5.00"); // published, never curated → absent

    UUID col = admin.create(s.orgId, "picks", "مختارات", "Picks", null).getId();
    admin.setListings(s.orgId, col, List.of(c, a, b));

    // Curated order is the default order — not newest, not price.
    assertEquals(List.of("c", "a", "b"), publicSlugs(s.slug, "picks"));
    assertEquals(3L, publicPage(s.slug, "picks").total());

    // Unpublish drops it from the landing page; re-publish restores its curated slot.
    listings.unpublish(s.orgId, a);
    assertEquals(List.of("c", "b"), publicSlugs(s.slug, "picks"));
    listings.publish(s.orgId, a);
    assertEquals(List.of("c", "a", "b"), publicSlugs(s.slug, "picks"));
  }

  @Test
  void publicRead_draftStagedListingIsInvisible_butAdminSeesIt() {
    Seed s = seed("acme");
    UUID live = listing(s, "live", "Live", "10.00");
    UUID staged = listingWithStatus(s, "staged", "Staged", "20.00", ListingStatus.DRAFT);
    UUID col = admin.create(s.orgId, "launch", "إطلاق", "Launch", null).getId();
    admin.setListings(s.orgId, col, List.of(staged, live));

    // The merchant staged a DRAFT for launch: stored and visible in curation, never served.
    assertEquals(List.of("staged", "live"), curatedSlugs(s.orgId, col));
    assertEquals(List.of("live"), publicSlugs(s.slug, "launch"));
  }

  @Test
  void publicRead_explicitSortOverridesCuratedOrder() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "30.00");
    UUID b = listing(s, "b", "Beta", "10.00");
    UUID c = listing(s, "c", "Gamma", "20.00");
    UUID col = admin.create(s.orgId, "picks", "مختارات", "Picks", null).getId();
    admin.setListings(s.orgId, col, List.of(a, b, c)); // curated a,b,c

    ListingPage p =
        storefront.listPublished(
            s.slug,
            null,
            "picks",
            null,
            null,
            null,
            "price_asc",
            null,
            null,
            null,
            java.util.Map.of(),
            null,
            0,
            20);
    assertEquals(List.of("b", "c", "a"), slugs(p));
  }

  @Test
  void publicRead_composesWithQueryPriceAndCategory() {
    Seed s = seed("acme");
    UUID cams = category(s.orgId, "Cameras", "cams");
    UUID gopro = listing(s, "gopro", "GoPro Hero", "150.00");
    UUID cheap = listing(s, "cheap-cam", "GoPro Mini", "50.00");
    UUID pen = listing(s, "pen", "Blue pen", "5.00");
    categorize(gopro, cams);
    categorize(cheap, cams);
    UUID col = admin.create(s.orgId, "picks", "مختارات", "Picks", null).getId();
    admin.setListings(s.orgId, col, List.of(pen, gopro, cheap));

    // collection ∧ q
    assertEquals(
        List.of("gopro", "cheap-cam"),
        slugs(
            storefront.listPublished(
                s.slug,
                null,
                "picks",
                "gopro",
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of(),
                null,
                0,
                20)));
    // collection ∧ price band
    assertEquals(
        List.of("gopro"),
        slugs(
            storefront.listPublished(
                s.slug,
                null,
                "picks",
                null,
                "100",
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of(),
                null,
                0,
                20)));
    // collection ∧ category
    assertEquals(
        List.of("gopro", "cheap-cam"),
        slugs(
            storefront.listPublished(
                s.slug,
                "cams",
                "picks",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of(),
                null,
                0,
                20)));
  }

  @Test
  void publicRead_unknownSlugIsEmpty200_blankIs400() {
    Seed s = seed("acme");
    listing(s, "a", "Alpha", "10.00");

    ListingPage ghost = publicPage(s.slug, "no-such-shelf");
    assertEquals(0L, ghost.total());
    assertTrue(ghost.items().isEmpty());

    // A present-but-blank parameter is a caller bug, not a stale link.
    ValidationException blank =
        assertThrows(ValidationException.class, () -> publicPage(s.slug, "  "));
    assertTrue(blank.getMessage().contains("collection"), blank.getMessage());
  }

  @Test
  void servlet_collectionParam_setsCacheHeader_andBlankIs400() throws Exception {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "10.00");
    UUID col = admin.create(s.orgId, "picks", "مختارات", "Picks", null).getId();
    admin.setListings(s.orgId, col, List.of(a));

    HttpServletResponse ok = driveServlet("/" + s.slug + "/listings", "collection", "picks");
    verify(ok).setStatus(200);
    verify(ok).setHeader("Cache-Control", "public, max-age=60");

    HttpServletResponse bad = driveServlet("/" + s.slug + "/listings", "collection", "");
    verify(bad).setStatus(400);

    // The rail read is on the slower profile tier — collections change at merchandising cadence.
    HttpServletResponse rail = driveServlet("/" + s.slug + "/collections");
    verify(rail).setStatus(200);
    verify(rail).setHeader("Cache-Control", "public, max-age=300");
  }

  // group 3: rail honesty + locale

  @Test
  void rail_omitsAllDraftCollections_andIncludesOneTheMomentItPublishes() {
    Seed s = seed("acme");
    UUID draft = listingWithStatus(s, "draft", "Draft", "10.00", ListingStatus.DRAFT);
    UUID empty = admin.create(s.orgId, "empty-shelf", "رف فارغ", "Empty shelf", 0).getId();
    UUID staged = admin.create(s.orgId, "staged-shelf", "رف مجهز", "Staged shelf", 1).getId();
    admin.setListings(s.orgId, staged, List.of(draft));

    // Neither an empty collection nor an all-draft one is advertised.
    assertTrue(storefront.collections(s.slug, null).isEmpty());

    // Publishing the staged listing makes exactly that collection appear.
    listings.publish(s.orgId, draft);
    assertEquals(List.of("staged-shelf"), railSlugs(s.slug, null));

    // The empty one still never appears — no listing, no shelf.
    assertFalse(railSlugs(s.slug, null).contains("empty-shelf"));
    assertNotNull(empty);
  }

  @Test
  void rail_resolvesLocaleNames_andHonoursRailOrder() {
    Seed s = seed("acme");
    UUID one = listing(s, "one", "One", "10.00");
    UUID two = listing(s, "two", "Two", "20.00");
    UUID first = admin.create(s.orgId, "first", "الأول", "First", 0).getId();
    UUID second = admin.create(s.orgId, "second", "الثاني", "Second", 1).getId();
    admin.setListings(s.orgId, first, List.of(one));
    admin.setListings(s.orgId, second, List.of(two));

    // Default locale (ar) — no ?locale=.
    assertEquals(List.of("الأول", "الثاني"), railNames(s.slug, null));
    // ?locale=en resolves the English names, rail order unchanged.
    assertEquals(List.of("First", "Second"), railNames(s.slug, "en"));

    // sort_order drives the rail, not creation time or slug: flip the two.
    admin.update(s.orgId, second, "second", "الثاني", "Second", 0);
    admin.update(s.orgId, first, "first", "الأول", "First", 1);
    assertEquals(List.of("second", "first"), railSlugs(s.slug, null));
  }

  @Test
  void rail_fallsBackToDefaultLocaleName_whenTheRequestedOneWasNeverAuthored() {
    Seed s = seed("acme");
    UUID one = listing(s, "one", "One", "10.00");
    UUID col = admin.create(s.orgId, "ar-only", "عربي فقط", null, 0).getId();
    admin.setListings(s.orgId, col, List.of(one));

    // No English name authored → the rail falls back to the default-locale name, never to a blank.
    assertEquals(List.of("عربي فقط"), railNames(s.slug, "en"));
  }

  // group 4: lifecycle

  @Test
  void lifecycle_deletingAListingCascadesItOut_deletingACollectionKeepsListings() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "10.00");
    UUID b = listing(s, "b", "Beta", "20.00");
    UUID col = admin.create(s.orgId, "picks", "مختارات", "Picks", null).getId();
    admin.setListings(s.orgId, col, List.of(a, b));

    // Deleting a listing takes its membership row with it — no admin action, no orphan.
    listings.delete(s.orgId, b);
    assertEquals(List.of("a"), curatedSlugs(s.orgId, col));
    assertEquals(1L, admin.getAll(s.orgId).get(0).listingCount());

    // Deleting the collection leaves the listing itself completely intact.
    admin.delete(s.orgId, col);
    assertTrue(admin.getAll(s.orgId).isEmpty());
    assertEquals("a", listings.getById(s.orgId, a).listing().getSlug());
  }

  @Test
  void lifecycle_slugRenameMovesTheLandingPage_oldSlugGoesEmpty() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "10.00");
    UUID col = admin.create(s.orgId, "ramadan-2025", "رمضان ٢٠٢٥", "Ramadan 2025", null).getId();
    admin.setListings(s.orgId, col, List.of(a));
    assertEquals(List.of("a"), publicSlugs(s.slug, "ramadan-2025"));

    admin.update(s.orgId, col, "ramadan-2026", "رمضان ٢٠٢٦", "Ramadan 2026", 0);
    assertEquals(List.of("a"), publicSlugs(s.slug, "ramadan-2026"));
    // The old slug is a stale bookmark: the calm empty page, never a 404.
    assertEquals(0L, publicPage(s.slug, "ramadan-2025").total());
  }

  @Test
  void lifecycle_unknownIdIs404_onEveryAdminRoute() {
    Seed s = seed("acme");
    UUID ghost = UUID.randomUUID();
    assertThrows(NotFoundException.class, () -> admin.getById(s.orgId, ghost));
    assertThrows(NotFoundException.class, () -> admin.getListings(s.orgId, ghost));
    assertThrows(NotFoundException.class, () -> admin.delete(s.orgId, ghost));
    assertThrows(NotFoundException.class, () -> admin.setListings(s.orgId, ghost, List.of()));
    assertThrows(
        NotFoundException.class, () -> admin.update(s.orgId, ghost, "x-y", "اسم", "Name", 0));
  }

  // group 5: no-leak + cross-org isolation

  @Test
  void publicRail_carriesOnlySlugAndName() throws Exception {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "10.00");
    UUID col = admin.create(s.orgId, "picks", "مختارات", "Picks", 3).getId();
    admin.setListings(s.orgId, col, List.of(a));

    String json =
        JSON.writeValueAsString(
            storefront.collections(s.slug, null).stream()
                .map(PublicCollectionResponse::from)
                .toList());
    assertTrue(json.contains("\"slug\":\"picks\""), json);
    for (String forbidden :
        List.of("\"id\"", "org_id", "sort_order", "listing_count", "created_at", "updated_at")) {
      assertFalse(json.contains(forbidden), "rail leaked '" + forbidden + "': " + json);
    }
    assertFalse(json.contains(col.toString()), json);
  }

  @Test
  void crossOrg_collectionsAndSlugsAreInvisible() {
    Seed mine = seed("acme");
    Seed theirs = seed("other");
    UUID myListing = listing(mine, "mine", "Mine", "10.00");
    UUID theirListing = listing(theirs, "theirs", "Theirs", "10.00");
    UUID myCol = admin.create(mine.orgId, "picks", "مختارات", "Picks", null).getId();
    UUID theirCol = admin.create(theirs.orgId, "picks", "مختارات", "Picks", null).getId();
    admin.setListings(mine.orgId, myCol, List.of(myListing));
    admin.setListings(theirs.orgId, theirCol, List.of(theirListing));

    // The same slug in two orgs is two different shelves; neither read crosses.
    assertEquals(List.of("mine"), publicSlugs(mine.slug, "picks"));
    assertEquals(List.of("theirs"), publicSlugs(theirs.slug, "picks"));
    // A foreign collection id is a 404 on the admin plane, not a leak.
    assertThrows(NotFoundException.class, () -> admin.getById(mine.orgId, theirCol));
    assertThrows(NotFoundException.class, () -> admin.delete(mine.orgId, theirCol));
    assertEquals(1, admin.getAll(mine.orgId).size());
  }

  // group 6: featured regression rider

  @Test
  void featuredAndPlainReadsAreUnaffectedByCollections() {
    Seed s = seed("acme");
    OffsetDateTime t0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    UUID older = listingAt(s, "older", "Older", "10.00", t0);
    UUID newer = listingAt(s, "newer", "Newer", "20.00", t0.plusDays(1));
    // Feature one, collect the other — the two curations are independent columns.
    listings.setFeatured(s.orgId, List.of(newer));
    UUID col = admin.create(s.orgId, "picks", "مختارات", "Picks", null).getId();
    admin.setListings(s.orgId, col, List.of(older));

    // ?featured=true is byte-identical to before: only the featured one, curated order.
    assertEquals(
        List.of("newer"),
        slugs(storefront.listPublished(s.slug, null, null, null, null, null, "true", 0, 20)));
    // The plain read still defaults to NEWEST over everything.
    assertEquals(
        List.of("newer", "older"),
        slugs(storefront.listPublished(s.slug, null, null, null, null, null, null, 0, 20)));
    // And ?collection= serves only the collected one.
    assertEquals(List.of("older"), publicSlugs(s.slug, "picks"));

    // featured ∧ collection intersect (empty here — different listings), and the collection's
    // curated order wins as the default when both are asked for.
    assertEquals(
        0L,
        storefront
            .listPublished(
                /* orgSlug= */ s.slug,
                /* categorySlug= */ null,
                /* collectionSlug= */ "picks",
                /* q= */ null,
                /* minPrice= */ null,
                /* maxPrice= */ null,
                /* sort= */ null,
                /* featured= */ "true",
                /* sold= */ null,
                /* locale= */ null,
                /* attributeFilters= */ java.util.Map.of(),
                /* includeFacets= */ null,
                /* page= */ 0,
                /* size= */ 20)
            .total());
  }

  // servlet harness

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

  // group: collection image (stories/collection_image.md)

  @Test
  void image_presignMintsOrgPrefixedKey_andForeignKeyIs400() {
    Seed s = seed("acme");
    Seed other = seed("rival");

    ImagePresign presign = admin.presignImageUpload(s.orgId, "picks.png", "image/png");
    assertTrue(presign.objectKey().startsWith(s.orgId + "/collection/"), presign.objectKey());
    assertTrue(presign.uploadUrl().startsWith("http"));
    assertTrue(presign.expiresInSeconds() > 0);

    // Another org's key, our own category-prefixed key, and a bare filename are all refused.
    for (String bad :
        List.of(
            other.orgId + "/collection/x.png",
            ObjectStorage.categoryKeyPrefix(s.orgId) + "x.png",
            "picks.png")) {
      assertThrows(
          ValidationException.class,
          () -> admin.create(s.orgId, "picks", "مختارات", "Picks", 0, bad),
          bad);
    }
    assertThrows(
        ValidationException.class, () -> admin.presignImageUpload(s.orgId, " ", "image/png"));
  }

  @Test
  void image_roundtrip_update_absentKeeps_blankClears_valueReplaces() {
    Seed s = seed("acme");
    String key = admin.presignImageUpload(s.orgId, "picks.png", "image/png").objectKey();

    Collection created = admin.create(s.orgId, "picks", "مختارات", "Picks", 0, key);
    assertEquals(key, created.getImageObjectKey());

    CollectionView view = admin.getById(s.orgId, created.getId());
    assertEquals(key, view.collection().getImageObjectKey());
    assertNotNull(view.imageUrl());
    assertTrue(view.imageUrl().startsWith("http"), view.imageUrl());
    assertEquals(key, admin.getAll(s.orgId).get(0).collection().getImageObjectKey());

    // Absent (the pre-image PUT shape) → unchanged.
    admin.update(s.orgId, created.getId(), "picks", "مختارات", "Picks", 0);
    assertEquals(key, admin.getById(s.orgId, created.getId()).collection().getImageObjectKey());

    // A new key → replaced.
    String key2 = admin.presignImageUpload(s.orgId, "picks-2.png", "image/png").objectKey();
    admin.update(s.orgId, created.getId(), "picks", "مختارات", "Picks", 0, key2);
    assertEquals(key2, admin.getById(s.orgId, created.getId()).collection().getImageObjectKey());

    // Blank → cleared, preview gone with it.
    admin.update(s.orgId, created.getId(), "picks", "مختارات", "Picks", 0, "  ");
    CollectionView cleared = admin.getById(s.orgId, created.getId());
    assertNull(cleared.collection().getImageObjectKey());
    assertNull(cleared.imageUrl());
  }

  @Test
  void image_publicRail_carriesPresignedUrl_nullWhenNone() {
    Seed s = seed("acme");
    UUID a = listing(s, "a", "Alpha", "10.00");
    String key = admin.presignImageUpload(s.orgId, "picks.png", "image/png").objectKey();
    Collection withImage = admin.create(s.orgId, "picks", "مختارات", "Picks", 0, key);
    Collection without = admin.create(s.orgId, "plain", "عادي", "Plain", 1, null);
    admin.setListings(s.orgId, withImage.getId(), List.of(a));
    admin.setListings(s.orgId, without.getId(), List.of(a));

    List<PublicCollectionView> rail = storefront.collections(s.slug, null);
    assertEquals(2, rail.size());
    assertEquals("picks", rail.get(0).slug());
    assertNotNull(rail.get(0).imageUrl());
    assertTrue(rail.get(0).imageUrl().startsWith("http"), rail.get(0).imageUrl());
    assertTrue(
        rail.get(0).imageUrl().contains(key.substring(key.lastIndexOf('/') + 1)), "key in URL");
    assertNull(rail.get(1).imageUrl());
  }

  // seeding + read helpers

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

  private UUID listing(Seed s, String slug, String title, String price) {
    return listingAt(s, slug, title, price, OffsetDateTime.now());
  }

  private UUID listingAt(
      Seed s, String slug, String title, String price, OffsetDateTime publishedAt) {
    return insert(s, slug, title, price, ListingStatus.PUBLISHED, publishedAt);
  }

  private UUID listingWithStatus(
      Seed s, String slug, String title, String price, ListingStatus status) {
    return insert(
        s,
        slug,
        title,
        price,
        status,
        status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null);
  }

  private UUID insert(
      Seed s,
      String slug,
      String title,
      String price,
      ListingStatus status,
      OffsetDateTime publishedAt) {
    ProductListing l = new ProductListing();
    l.setOrgId(s.orgId);
    l.setProductId(product(s.orgId));
    l.setSlug(slug);
    l.setSalesPrice(new BigDecimal(price));
    l.setStatus(status);
    l.setPublishedAt(publishedAt);
    UUID id = listingRepo().insert(l).getId();
    // The title lives in the translation table since L6 — author the default-locale row so the
    // ?q= composition test has something to match.
    listingRepo()
        .replaceTranslations(id, List.of(new ProductListingTranslation("ar", title, title)));
    return id;
  }

  private ProductListingRepository listingRepo() {
    return new ProductListingRepositoryFactoryImpl().create(dsl);
  }

  private List<String> curatedSlugs(UUID orgId, UUID collectionId) {
    return admin.getListings(orgId, collectionId).stream().map(v -> v.listing().getSlug()).toList();
  }

  private ListingPage publicPage(String orgSlug, String collectionSlug) {
    return storefront.listPublished(
        /* orgSlug= */ orgSlug,
        /* categorySlug= */ null,
        /* collectionSlug= */ collectionSlug,
        /* q= */ null,
        /* minPrice= */ null,
        /* maxPrice= */ null,
        /* sort= */ null,
        /* featured= */ null,
        /* sold= */ null,
        /* locale= */ null,
        /* attributeFilters= */ java.util.Map.of(),
        /* includeFacets= */ null,
        /* page= */ 0,
        /* size= */ 20);
  }

  private List<String> publicSlugs(String orgSlug, String collectionSlug) {
    return slugs(publicPage(orgSlug, collectionSlug));
  }

  private List<String> railSlugs(String orgSlug, String locale) {
    return storefront.collections(orgSlug, locale).stream()
        .map(PublicCollectionView::slug)
        .toList();
  }

  private List<String> railNames(String orgSlug, String locale) {
    return storefront.collections(orgSlug, locale).stream()
        .map(PublicCollectionView::name)
        .toList();
  }

  private static List<String> slugs(ListingPage p) {
    return p.items().stream().map(StorefrontService.ListingView::slug).toList();
  }
}
