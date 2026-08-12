package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER_WISHLIST;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerWishlistRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.ListingView;
import com.loai.inventory.service.WishlistService;
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
 * Customer wishlist (roadmap item 3, {@code stories/customer_wishlist.md}) against real Postgres,
 * driving {@link WishlistService}. The properties under test are the ones the frontend's
 * guest-merge-on-login depends on, plus the two isolation rules the portal plane lives by:
 *
 * <ul>
 *   <li><b>Idempotence everywhere</b> — the login-merge replays a guest's whole list on every
 *       sign-in, so a repeated add must converge to one row and a repeated remove must not fail.
 *   <li><b>Resolve at any status, serve only PUBLISHED</b> — an unpublished saved listing goes
 *       quiet and comes back on republish; the saved row is never destroyed behind the customer's
 *       back. Deleting the listing itself does cascade it away.
 *   <li><b>Identity is the principal</b> — another customer, and the same email in another org,
 *       cannot see or touch these rows.
 *   <li><b>The card shape is the storefront's</b> — no id, no product_id, no status crosses.
 * </ul>
 */
@Testcontainers
class PortalWishlistIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static WishlistService service;
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

    StorefrontService storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new StorefrontBannerRepositoryFactoryImpl(),
            new ListingReviewRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CollectionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl(),
            storage,
            // The wishlist read never places an order.
            null,
            null);
    service =
        new WishlistService(
            dsl,
            new CustomerWishlistRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            storefront);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE customer_wishlist, product_listing_image, product_listing_translation,"
            + " product_listing, inventory, product, customer, org RESTART IDENTITY CASCADE");
  }

  // ───────── 1 · roundtrip: add, read as cards, remove, re-remove ─────────

  @Test
  void addTwo_readsBackNewestFirst_thenRemoveIsIdempotent() {
    Seed s = seed();
    listing(s.orgId, "kettle", ListingStatus.PUBLISHED);
    listing(s.orgId, "beans", ListingStatus.PUBLISHED);

    service.add(s.orgId, s.customerId, "kettle");
    service.add(s.orgId, s.customerId, "beans");

    // Newest saved first — the order the account page renders.
    assertEquals(List.of("beans", "kettle"), slugs(list(s)));

    service.remove(s.orgId, s.customerId, "kettle");
    assertEquals(List.of("beans"), slugs(list(s)));

    // Removing it again is a success, not a 404 — the heart is already off.
    service.remove(s.orgId, s.customerId, "kettle");
    assertEquals(List.of("beans"), slugs(list(s)));
  }

  @Test
  void savedCards_carryTheStorefrontShape_stockAndTitle() {
    Seed s = seed();
    UUID listingId = listing(s.orgId, "kettle", ListingStatus.PUBLISHED);
    stock(s.orgId, productOf(listingId), 4);
    service.add(s.orgId, s.customerId, "kettle");

    ListingView view = list(s).get(0);
    assertEquals("kettle", view.slug());
    assertEquals("kettle title", view.title());
    assertEquals(0, new BigDecimal("19.99").compareTo(view.salesPrice()));
    assertTrue(view.inStock(), "availability is enriched exactly like a catalog card");
    // No approved reviews → absent, never a fabricated zero.
    assertEquals(null, view.ratingAvg());
    assertEquals(null, view.ratingCount());
  }

  // ───────── 2 · idempotent add — what the login-merge replay depends on ─────────

  @Test
  void addingTheSameSlugTwice_isOneRow_andBothCallsSucceed() {
    Seed s = seed();
    listing(s.orgId, "kettle", ListingStatus.PUBLISHED);

    service.add(s.orgId, s.customerId, "kettle");
    service.add(s.orgId, s.customerId, "kettle");
    service.add(s.orgId, s.customerId, "kettle");

    assertEquals(1, rowCount(s.orgId, s.customerId), "UNIQUE(customer, listing) collapses replays");
    assertEquals(List.of("kettle"), slugs(list(s)));
  }

  // ───────── 3 · publish lifecycle: the row survives, the card doesn't ─────────

  @Test
  void unpublishingHidesTheCard_butKeepsTheRow_andRepublishBringsItBack() {
    Seed s = seed();
    UUID listingId = listing(s.orgId, "kettle", ListingStatus.PUBLISHED);
    listing(s.orgId, "beans", ListingStatus.PUBLISHED);
    service.add(s.orgId, s.customerId, "kettle");
    service.add(s.orgId, s.customerId, "beans");

    setStatus(listingId, ListingStatus.DRAFT);
    assertEquals(List.of("beans"), slugs(list(s)), "an unpublished listing stops serving");
    assertEquals(2, rowCount(s.orgId, s.customerId), "…but the save is not destroyed");

    setStatus(listingId, ListingStatus.PUBLISHED);
    assertEquals(
        List.of("beans", "kettle"), slugs(list(s)), "republish restores it, in save order");
  }

  @Test
  void deletingTheListing_cascadesTheSavedRowAway() {
    Seed s = seed();
    UUID listingId = listing(s.orgId, "kettle", ListingStatus.PUBLISHED);
    service.add(s.orgId, s.customerId, "kettle");

    new ProductListingRepositoryFactoryImpl().create(dsl).deleteById(s.orgId, listingId);

    assertEquals(0, rowCount(s.orgId, s.customerId), "ON DELETE CASCADE — no orphan to reconcile");
    assertTrue(list(s).isEmpty());
  }

  // ───────── 4 · isolation: the principal is the identity ─────────

  @Test
  void anotherCustomer_neverSeesOrRemovesTheseRows() {
    Seed s = seed();
    listing(s.orgId, "kettle", ListingStatus.PUBLISHED);
    UUID other = customer(s.orgId, "other@acme.test", "Other");
    service.add(s.orgId, s.customerId, "kettle");

    assertTrue(
        service.list(s.orgId, other, null).isEmpty(),
        "customer B's wishlist is B's, and it's empty");

    // B removing the same slug touches nothing of A's.
    service.remove(s.orgId, other, "kettle");
    assertEquals(List.of("kettle"), slugs(list(s)));
  }

  @Test
  void theSameEmailInAnotherOrg_isADifferentWishlist() {
    Seed a = seed();
    Seed b = seed();
    listing(a.orgId, "kettle", ListingStatus.PUBLISHED);
    listing(b.orgId, "kettle", ListingStatus.PUBLISHED);
    service.add(a.orgId, a.customerId, "kettle");

    assertTrue(service.list(b.orgId, b.customerId, null).isEmpty());
    // And org B's customer can't reach org A's row even with A's customer id — the pair is scoped.
    assertTrue(service.list(b.orgId, a.customerId, null).isEmpty());
  }

  // ───────── 5 · unknown slug, and the cap ─────────

  @Test
  void unknownSlug_isAnOpaque404_onAddAndRemove() {
    Seed s = seed();
    assertThrows(NotFoundException.class, () -> service.add(s.orgId, s.customerId, "ghost"));
    assertThrows(NotFoundException.class, () -> service.remove(s.orgId, s.customerId, "ghost"));
    // A blank slug is a caller error, not a lookup.
    assertThrows(ValidationException.class, () -> service.add(s.orgId, s.customerId, "  "));
  }

  @Test
  void atTheCap_aNewSaveIs400_butReSavingAnExistingOneStillSucceeds() {
    Seed s = seed();
    for (int i = 0; i < 200; i++) {
      String slug = String.format("item-%03d", i);
      listing(s.orgId, slug, ListingStatus.PUBLISHED);
      service.add(s.orgId, s.customerId, slug);
    }
    listing(s.orgId, "one-too-many", ListingStatus.PUBLISHED);

    ValidationException e =
        assertThrows(
            ValidationException.class, () -> service.add(s.orgId, s.customerId, "one-too-many"));
    assertTrue(e.getMessage().toLowerCase().contains("full"), e.getMessage());
    assertEquals(200, rowCount(s.orgId, s.customerId));

    // The cap refuses growth, not the call: the merge replay of an already-saved slug must not
    // start failing just because the customer is at the limit.
    service.add(s.orgId, s.customerId, "item-042");
    assertEquals(200, rowCount(s.orgId, s.customerId));
  }

  // ───────── 6 · DRAFT heart: resolve at any status, serve only PUBLISHED ─────────

  @Test
  void heartingADraftListing_storesTheRow_andServesItOnlyAfterPublish() {
    Seed s = seed();
    UUID draft = listing(s.orgId, "coming-soon", ListingStatus.DRAFT);

    service.add(s.orgId, s.customerId, "coming-soon");
    assertEquals(1, rowCount(s.orgId, s.customerId), "the heart lands even pre-publish");
    assertTrue(list(s).isEmpty(), "…but a shopper never sees an unbuyable card");

    setStatus(draft, ListingStatus.PUBLISHED);
    assertEquals(List.of("coming-soon"), slugs(list(s)));
  }

  // ───────── no-leak: the wire shape is the storefront card ─────────

  @Test
  void serializedRows_carryNoInternalFields() throws Exception {
    Seed s = seed();
    listing(s.orgId, "kettle", ListingStatus.PUBLISHED);
    service.add(s.orgId, s.customerId, "kettle");

    List<PublicListingResponse> data = list(s).stream().map(PublicListingResponse::from).toList();
    String json = JSON.writeValueAsString(data);

    for (String forbidden : List.of("product_id", "\"id\"", "status", "customer_id", "org_id")) {
      assertFalse(json.contains(forbidden), "leaked " + forbidden + " in " + json);
    }
    assertTrue(json.contains("kettle"), json);
  }

  // ───────── helpers ─────────

  private List<ListingView> list(Seed s) {
    return service.list(s.orgId, s.customerId, null);
  }

  private static List<String> slugs(List<ListingView> views) {
    return views.stream().map(ListingView::slug).toList();
  }

  private int rowCount(UUID orgId, UUID customerId) {
    return dsl.fetchCount(
        dsl.selectFrom(CUSTOMER_WISHLIST)
            .where(
                CUSTOMER_WISHLIST
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER_WISHLIST.CUSTOMER_ID.eq(customerId))));
  }

  private record Seed(UUID orgId, String orgSlug, UUID customerId) {}

  private Seed seed() {
    UUID orgId = UUID.randomUUID();
    String slug = "store-" + orgId;
    dsl.insertInto(ORG)
        .set(ORG.ID, orgId)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    return new Seed(orgId, slug, customer(orgId, "hussin@acme.test", "Hussin"));
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

  private UUID listing(UUID orgId, String slug, ListingStatus status) {
    ProductListing l = new ProductListing();
    l.setOrgId(orgId);
    l.setProductId(product(orgId));
    l.setTitle(slug + " title");
    l.setSlug(slug);
    l.setSalesPrice(new BigDecimal("19.99"));
    l.setStatus(status);
    l.setPublishedAt(status == ListingStatus.PUBLISHED ? OffsetDateTime.now() : null);
    UUID listingId = new ProductListingRepositoryFactoryImpl().create(dsl).insert(l).getId();
    for (String lang : new String[] {"ar", "en"}) {
      dsl.insertInto(PRODUCT_LISTING_TRANSLATION)
          .set(PRODUCT_LISTING_TRANSLATION.LISTING_ID, listingId)
          .set(PRODUCT_LISTING_TRANSLATION.LANGUAGE, lang)
          .set(PRODUCT_LISTING_TRANSLATION.TITLE, slug + " title")
          .execute();
    }
    return listingId;
  }

  private UUID productOf(UUID listingId) {
    return dsl.select(PRODUCT_LISTING.PRODUCT_ID)
        .from(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ID.eq(listingId))
        .fetchOne(PRODUCT_LISTING.PRODUCT_ID);
  }

  private void stock(UUID orgId, UUID productId, int qty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, orgId)
        .set(INVENTORY.PRODUCT_ID, productId)
        .set(INVENTORY.STOCK_QTY, qty)
        .execute();
  }

  private void setStatus(UUID listingId, ListingStatus status) {
    dsl.update(PRODUCT_LISTING)
        .set(
            PRODUCT_LISTING.STATUS,
            com.loai.inventory.repository.generated.enums.ListingStatus.valueOf(status.name()))
        .where(PRODUCT_LISTING.ID.eq(listingId))
        .execute();
  }
}
