package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.api.dto.PublicListingsPageResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductVariantService;
import com.loai.inventory.service.ProductVariantService.AttributeInput;
import com.loai.inventory.service.ProductVariantService.ValueInput;
import com.loai.inventory.service.ProductVariantService.VariantInput;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.FacetGroup;
import com.loai.inventory.service.StorefrontService.FacetValue;
import com.loai.inventory.service.StorefrontService.ListingPage;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Attribute facets ({@code stories/storefront_attribute_facets.md}, roadmap item 7): the {@code
 * attr_{slug}=v1,v2} filter grammar and the opt-in per-value counts, riding the variants epic's
 * normalized attribute substrate.
 *
 * <p>The two claims that matter. <b>Filtering</b> means "EXISTS an ACTIVE variant carrying this
 * value" — so a retired option stops narrowing, and a parent-only listing matches no facet at all.
 * <b>Counting</b> is standard multi-select: an attribute's own selection never narrows its own
 * counts, or picking "M" would render "L (0)" and dead-end the shopper on the filter they are
 * using.
 */
@Testcontainers
class AttributeFacetsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService storefront;
  static ProductVariantService variants;
  static ProductListingService listings;
  static CategoryService categories;
  static InventoryService inventory;
  static ObjectStorage storage;
  static final ObjectMapper JSON = ObjectMapperProvider.build();

  private final ActorContext actor = ActorContext.user(UUID.randomUUID().toString());

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

    storefront =
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
            new com.loai.inventory.repository.OrgPaymobConfigRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontCrawlRepositoryFactoryImpl(),
            storage,
            null,
            null);
    variants =
        new ProductVariantService(
            dsl,
            new ProductVariantRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new ProductRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
    listings =
        new ProductListingService(
            dsl,
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new ProductVariantRepositoryFactoryImpl(),
            storage,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    categories =
        new CategoryService(
            dsl,
            new CategoryRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.storage());
    inventory =
        new InventoryService(
            dsl,
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new ProductRepositoryImpl(dsl),
            new SalesOrderRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE variant_attribute_value, product_variant, attribute_value_translation,"
            + " attribute_value, attribute_translation, attribute, product_listing_image,"
            + " product_listing_category, product_listing_translation, product_listing, category,"
            + " inventory_log, inventory, product, org RESTART IDENTITY CASCADE");
  }

  // ═════════ 1 · filtering ═════════

  @Test
  void attrFilter_narrowsToListingsWithAMatchingActiveVariant() {
    Shop shop = seed();
    // shirt: size m,l × color red   |  hoodie: size l only, color blue  |  mug: no variants
    assertEquals(List.of("shirt"), slugs(search(shop, Map.of("size", List.of("m")))));
    assertEquals(
        List.of("hoodie", "shirt"),
        slugs(search(shop, Map.of("size", List.of("m", "l")))),
        "a comma list ORs within the attribute");
  }

  @Test
  void attrFilters_andAcrossAttributes() {
    Shop shop = seed();
    // The shirt satisfies size=l AND color=red; the hoodie is l but blue.
    assertEquals(
        List.of("shirt"),
        slugs(search(shop, Map.of("size", List.of("l"), "color", List.of("red")))));
    assertEquals(
        List.of(),
        slugs(search(shop, Map.of("size", List.of("m"), "color", List.of("blue")))),
        "no listing satisfies both");
  }

  @Test
  void aParentOnlyListingMatchesNoFacet_andAnInactiveVariantsValueStopsMatching() {
    Shop shop = seed();
    assertFalse(
        slugs(search(shop, Map.of("size", List.of("m", "l")))).contains("mug"),
        "a listing with no variants can never satisfy an attr_* filter");

    // Retire the hoodie's only size — its value must stop narrowing to it.
    variants.replaceVariants(shop.org, shop.hoodieId, List.of(sizeAxis(), colorAxis()), List.of());
    assertEquals(List.of("shirt"), slugs(search(shop, Map.of("size", List.of("m", "l")))));
  }

  @Test
  void composesWithQ_price_category_andBestSelling() {
    Shop shop = seed();
    // q + attr
    assertEquals(
        List.of("shirt"),
        slugs(
            storefront.listPublished(
                shop.orgSlug,
                null,
                "Shirt",
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("size", List.of("m")),
                null,
                0,
                20)));
    // price band that excludes the shirt's 179 "from" price → nothing left
    assertEquals(
        List.of(),
        slugs(
            storefront.listPublished(
                shop.orgSlug,
                null,
                null,
                "500",
                null,
                null,
                null,
                null,
                null,
                Map.of("size", List.of("m")),
                null,
                0,
                20)));
    // category + attr
    assertEquals(
        List.of("shirt"),
        slugs(
            storefront.listPublished(
                shop.orgSlug,
                "apparel",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("size", List.of("m")),
                null,
                0,
                20)));
    // sort=best_selling still composes (nothing sold → both rank last, slug order)
    assertEquals(
        List.of("hoodie", "shirt"),
        slugs(
            storefront.listPublished(
                shop.orgSlug,
                null,
                null,
                null,
                null,
                "best_selling",
                null,
                null,
                null,
                Map.of("size", List.of("m", "l")),
                null,
                0,
                20)));
  }

  @Test
  void aDraftListingNeverAppears_howeverItsVariantsAreTagged() {
    Shop shop = seed();
    listings.unpublish(shop.org, shop.shirtId);
    assertEquals(List.of("hoodie"), slugs(search(shop, Map.of("size", List.of("m", "l")))));
  }

  // ═════════ 2 · grammar ═════════

  @Test
  void malformedShapes_are400sNamingTheParameter() {
    Shop shop = seed();

    ValidationException empty =
        assertThrows(
            ValidationException.class, () -> search(shop, Map.of("size", List.of("  ", ""))));
    assertTrue(empty.getMessage().contains("attr_size"), empty.getMessage());

    assertThrows(ValidationException.class, () -> search(shop, Map.of("  ", List.of("m"))));

    Map<String, List<String>> elevenValues =
        Map.of("size", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"));
    ValidationException tooManyValues =
        assertThrows(ValidationException.class, () -> search(shop, elevenValues));
    assertTrue(tooManyValues.getMessage().contains("attr_size"), tooManyValues.getMessage());

    Map<String, List<String>> sixAttributes = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 6; i++) {
      sixAttributes.put("axis" + i, List.of("v"));
    }
    assertThrows(ValidationException.class, () -> search(shop, sixAttributes));
  }

  @Test
  void anUnknownAttributeOrValueSlug_isAnEmptyResult_notAnError() {
    Shop shop = seed();
    // A merchant deleting an attribute must not turn every stale bookmark into an error wall.
    assertEquals(List.of(), slugs(search(shop, Map.of("fabric", List.of("linen")))));
    assertEquals(List.of(), slugs(search(shop, Map.of("size", List.of("xxl")))));
  }

  @Test
  void caseVariantAttrKeys_foldTogether_ratherThanOneSilentlyWinning() {
    Shop shop = seed();
    // The servlet keys the map on the RAW parameter name, so `?attr_Size=m&attr_size=l` arrives
    // here as two entries. The service lower-cases the name to canonicalize it -- at which point
    // one used to overwrite the other, and half the shopper's selection vanished with no 400 to
    // say so. Two spellings of one attribute are one attribute: their values OR together, exactly
    // as `attr_size=m,l` does.
    // Both orderings, because "last one wins" and "first one wins" are each wrong in one direction
    // and a single ordering can pass by luck: here size=m alone matches only the shirt while
    // size=l matches both, so a dropped half is visible in the result either way.
    List<String> merged = slugs(search(shop, Map.of("size", List.of("m", "l"))));
    Map<String, List<String>> upperFirst = new java.util.LinkedHashMap<>();
    upperFirst.put("Size", List.of("l"));
    upperFirst.put("size", List.of("m"));
    Map<String, List<String>> lowerFirst = new java.util.LinkedHashMap<>();
    lowerFirst.put("size", List.of("m"));
    lowerFirst.put("Size", List.of("l"));
    assertEquals(merged, slugs(search(shop, upperFirst)), "attr_Size=l + attr_size=m");
    assertEquals(merged, slugs(search(shop, lowerFirst)), "attr_size=m + attr_Size=l");

    // And the ≤5 cap counts ATTRIBUTES, not spellings: six casings of one axis is one filter, so
    // it must not be rejected as "at most 5 attr_* filters".
    Map<String, List<String>> sixCasingsOfOneAxis = new java.util.LinkedHashMap<>();
    for (String spelling : List.of("size", "Size", "SIZE", "SiZe", "sIzE", "siZE")) {
      sixCasingsOfOneAxis.put(spelling, List.of("m"));
    }
    assertEquals(
        slugs(search(shop, Map.of("size", List.of("m")))),
        slugs(search(shop, sixCasingsOfOneAxis)),
        "six spellings of one axis is one filter, not six");
  }

  @Test
  void includeFacets_isCaseSensitive_likeItsSiblingBooleanParams() {
    Shop shop = seed();
    // One endpoint, one wire convention: `sold` and `featured` reject "TRUE" outright. There is no
    // reason for include_facets to be the one parameter that quietly accepts a different spelling,
    // and a client that gets away with `TRUE` here learns a rule the next parameter breaks.
    assertThrows(
        ValidationException.class,
        () ->
            storefront.listPublished(
                shop.orgSlug,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "TRUE",
                null,
                Map.of(),
                null,
                0,
                20),
        "sold=TRUE is rejected");
    assertThrows(
        ValidationException.class,
        () ->
            storefront.listPublished(
                shop.orgSlug,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                "TRUE",
                0,
                20),
        "so include_facets=TRUE must be too");
  }

  @Test
  void includeFacets_acceptsOnlyTrue() {
    Shop shop = seed();
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                storefront.listPublished(
                    shop.orgSlug,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    "maybe",
                    0,
                    20));
    assertTrue(e.getMessage().contains("include_facets"), e.getMessage());
    // absent and "true" both work
    assertTrue(facets(shop, Map.of()).size() > 0);
  }

  // ═════════ 3 · counts ═════════

  @Test
  void counts_areDistinctListings_soTwoRedVariantsOnOneListingCountOnce() {
    Shop shop = seed();
    // The shirt has TWO red variants (red-m, red-l) but is ONE page.
    assertEquals(1L, countOf(facets(shop, Map.of()), "color", "red"));
  }

  @Test
  void multiSelect_anAttributesOwnSelectionDoesNotNarrowItsOwnCounts() {
    Shop shop = seed();
    List<FacetGroup> withM = facets(shop, Map.of("size", List.of("m")));

    // Selecting M keeps L visible AND correctly counted — the sibling is still reachable.
    assertEquals(1L, countOf(withM, "size", "m"));
    assertEquals(2L, countOf(withM, "size", "l"), "both listings still have an L");
    assertTrue(selectedOf(withM, "size", "m"), "the selection echoes back for the checkbox state");
    assertFalse(selectedOf(withM, "size", "l"));

    // …while the OTHER attribute's counts DO narrow to the size=m scope (only the shirt).
    assertEquals(1L, countOf(withM, "color", "red"));
    assertEquals(0L, countOf(withM, "color", "blue"), "blue is only on the hoodie, which has no M");
  }

  @Test
  void counts_respectTheRestOfThePredicate() {
    Shop shop = seed();
    // A price band that excludes the hoodie (from 300) shrinks size=l from 2 listings to 1.
    List<FacetGroup> banded =
        storefront
            .listPublished(
                shop.orgSlug,
                null,
                null,
                null,
                "200",
                null,
                null,
                null,
                null,
                Map.of(),
                "true",
                0,
                20)
            .facets();
    assertEquals(1L, countOf(banded, "size", "l"));
  }

  @Test
  void attributesWithNoInScopeValue_areOmittedEntirely() {
    Shop shop = seed();
    // Narrow to the hoodie (blue): the shirt's red disappears, and so must any axis left empty.
    List<FacetGroup> blue = facets(shop, Map.of("color", List.of("blue")));
    assertTrue(
        blue.stream().allMatch(g -> !g.values().isEmpty()),
        "an empty group is chrome that tells the shopper nothing");
    // A store with no variants at all reports no facet groups — no filter UI to render.
    Shop bare = seedBareOrg();
    assertEquals(List.of(), facets(bare, Map.of()));
  }

  @Test
  void labelsResolveToTheRequestedLocale() {
    Shop shop = seed();
    List<FacetGroup> ar =
        storefront
            .listPublished(
                shop.orgSlug,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "ar",
                Map.of(),
                "true",
                0,
                20)
            .facets();
    FacetGroup size = groupOf(ar, "size");
    assertEquals("المقاس", size.label());
    assertTrue(
        size.values().stream().anyMatch(v -> "متوسط".equals(v.label())),
        "value labels resolve too — got " + size.values());
  }

  @Test
  void valuesAreOrderedByCountDescThenSlug() {
    Shop shop = seed();
    FacetGroup size = groupOf(facets(shop, Map.of()), "size");
    // l is on both listings (2), m on one (1) → l first, and ties fall to slug order.
    assertEquals(List.of("l", "m"), size.values().stream().map(FacetValue::slug).toList());
  }

  // ═════════ 4 · the lean envelope is unchanged ═════════

  @Test
  void withoutIncludeFacets_theEnvelopeIsByteIdenticalToTheNonFacetedRead() throws Exception {
    Shop shop = seed();
    ListingPage lean = storefront.listPublished(shop.orgSlug, null, 0, 20);
    ListingPage explicitlyNoFacets =
        storefront.listPublished(
            shop.orgSlug, null, null, null, null, null, null, null, null, Map.of(), null, 0, 20);

    assertEquals(json(lean), json(explicitlyNoFacets));
    assertFalse(json(lean).contains("facets"), "no facets key at all — got " + json(lean));

    // …and asking for them adds exactly that one key.
    ListingPage faceted =
        storefront.listPublished(
            shop.orgSlug, null, null, null, null, null, null, null, null, Map.of(), "true", 0, 20);
    assertTrue(json(faceted).contains("\"facets\""));
    assertTrue(json(faceted).contains("\"attribute\""));
  }

  // ═════════ helpers ═════════

  private record Shop(UUID org, String orgSlug, UUID shirtId, UUID hoodieId) {}

  /**
   * Two variant listings and one without. The shirt is size(m,l) × color(red); the hoodie is
   * size(l) × color(blue); the mug has no variants at all.
   */
  private Shop seed() {
    UUID org = createOrg("acme");
    Category apparel = categories.create(org, "Apparel", "apparel", null);

    ProductListing shirt =
        listings.create(org, createProduct(org, "SHIRT"), "Shirt", null, "shirt", price("199"));
    listings.publish(org, shirt.getId());
    listings.setCategories(org, shirt.getId(), java.util.Set.of(apparel.getId()));
    variants.replaceVariants(
        org,
        shirt.getId(),
        List.of(sizeAxis(), colorAxis()),
        List.of(variant("m", "red", "SH-RM", "249.00"), variant("l", "red", "SH-RL", "179.00")));

    ProductListing hoodie =
        listings.create(org, createProduct(org, "HOODIE"), "Hoodie", null, "hoodie", price("399"));
    listings.publish(org, hoodie.getId());
    variants.replaceVariants(
        org,
        hoodie.getId(),
        List.of(sizeAxis(), colorAxis()),
        List.of(variant("l", "blue", "HO-BL", "300.00")));

    ProductListing mug =
        listings.create(org, createProduct(org, "MUG"), "Mug", null, "mug", price("50"));
    listings.publish(org, mug.getId());

    return new Shop(org, orgSlug(org), shirt.getId(), hoodie.getId());
  }

  /** An org with a published, variant-less catalog — the "no filter UI at all" case. */
  private Shop seedBareOrg() {
    UUID org = createOrg("bare");
    ProductListing mug =
        listings.create(org, createProduct(org, "BMUG"), "Mug", null, "mug", price("50"));
    listings.publish(org, mug.getId());
    return new Shop(org, orgSlug(org), mug.getId(), mug.getId());
  }

  private ListingPage search(Shop shop, Map<String, List<String>> attrs) {
    return storefront.listPublished(
        shop.orgSlug, null, null, null, null, null, null, null, null, attrs, null, 0, 20);
  }

  private List<FacetGroup> facets(Shop shop, Map<String, List<String>> attrs) {
    return storefront
        .listPublished(
            shop.orgSlug, null, null, null, null, null, null, null, null, attrs, "true", 0, 20)
        .facets();
  }

  private static List<String> slugs(ListingPage page) {
    return page.items().stream().map(StorefrontService.ListingView::slug).sorted().toList();
  }

  private static FacetGroup groupOf(List<FacetGroup> facets, String attribute) {
    return facets.stream()
        .filter(g -> g.slug().equals(attribute))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no facet group " + attribute + " in " + facets));
  }

  /** The count for a value, or 0 when the group/value is (correctly) absent from the response. */
  private static long countOf(List<FacetGroup> facets, String attribute, String value) {
    return facets.stream()
        .filter(g -> g.slug().equals(attribute))
        .flatMap(g -> g.values().stream())
        .filter(v -> v.slug().equals(value))
        .mapToLong(FacetValue::count)
        .findFirst()
        .orElse(0L);
  }

  private static boolean selectedOf(List<FacetGroup> facets, String attribute, String value) {
    return facets.stream()
        .filter(g -> g.slug().equals(attribute))
        .flatMap(g -> g.values().stream())
        .filter(v -> v.slug().equals(value))
        .anyMatch(FacetValue::selected);
  }

  /** Serialize the actual wire envelope — the DTO, not the service record. */
  private static String json(ListingPage page) throws Exception {
    List<PublicListingResponse> data =
        page.items().stream().map(PublicListingResponse::from).toList();
    return JSON.writeValueAsString(
        PublicListingsPageResponse.of(data, page.total(), page.page(), page.size(), page.facets()));
  }

  private static AttributeInput sizeAxis() {
    return new AttributeInput(
        "size",
        Map.of("en", "Size", "ar", "المقاس"),
        List.of(
            new ValueInput("m", Map.of("en", "M", "ar", "متوسط")),
            new ValueInput("l", Map.of("en", "L", "ar", "كبير"))));
  }

  private static AttributeInput colorAxis() {
    return new AttributeInput(
        "color",
        Map.of("en", "Color", "ar", "اللون"),
        List.of(
            new ValueInput("red", Map.of("en", "Red", "ar", "أحمر")),
            new ValueInput("blue", Map.of("en", "Blue", "ar", "أزرق"))));
  }

  private static VariantInput variant(String size, String color, String sku, String price) {
    return new VariantInput(
        null, Map.of("size", size, "color", color), new BigDecimal(price), sku, null, true);
  }

  private static BigDecimal price(String v) {
    return new BigDecimal(v);
  }

  private UUID createOrg(String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, name)
        .set(ORG.SLUG, name + "-" + id)
        .set(ORG.ACTIVE, true)
        .set(ORG.DEFAULT_LOCALE, "en")
        .execute();
    return id;
  }

  private String orgSlug(UUID org) {
    return dsl.select(ORG.SLUG).from(ORG).where(ORG.ID.eq(org)).fetchOne(ORG.SLUG);
  }

  private UUID createProduct(UUID org, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku)
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  @Test
  void seedIsWellFormed() {
    Shop shop = seed();
    List<String> all = new ArrayList<>(slugs(search(shop, Map.of())));
    assertEquals(List.of("hoodie", "mug", "shirt"), all);
    // The inventory service is wired but unused by the facet path — stock never affects a facet.
    assertTrue(inventory != null && actor != null);
  }
}
