package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ATTRIBUTE;
import static com.loai.inventory.repository.generated.Tables.ATTRIBUTE_VALUE;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_VARIANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.ProductVariantService;
import com.loai.inventory.service.ProductVariantService.AttributeInput;
import com.loai.inventory.service.ProductVariantService.ValueInput;
import com.loai.inventory.service.ProductVariantService.VariantInput;
import com.loai.inventory.service.ProductVariantService.VariantRow;
import com.loai.inventory.service.ProductVariantService.VariantSetView;
import com.loai.inventory.service.StorefrontService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Slice VG1 ({@code stories/catalog_variants_model.md}): the variant model + the admin set-replace
 * surface, the child-product lifecycle behind it, and the two 1:1 guards.
 *
 * <p>The load-bearing claim under test is the architecture's central bet (§1): because a variant IS
 * a child {@code product}, per-variant stock, restock and barcode scanning work through the
 * <em>existing</em> inventory code with nothing new — {@link #childProducts_areOrdinaryStockRows()}
 * is what proves it. The other load-bearing claim is that this slice leaks nothing: {@link
 * #publicListingRead_isUnchangedByVariants()} pins the public detail byte-for-byte.
 */
@Testcontainers
class CatalogVariantsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ProductVariantService variants;
  static ProductListingService listings;
  static ProductService products;
  static InventoryService inventory;
  static StorefrontService storefront;
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
            storage);
    products =
        new ProductService(
            new ProductRepositoryImpl(dsl), new ProductVariantRepositoryFactoryImpl(), dsl);
    inventory =
        new InventoryService(
            dsl,
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new ProductRepositoryImpl(dsl),
            new SalesOrderRepositoryFactoryImpl());
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
        "TRUNCATE variant_attribute_value, product_variant, attribute_value_translation,"
            + " attribute_value, attribute_translation, attribute, product_listing_image,"
            + " product_listing_category, product_listing_translation, product_listing, category,"
            + " inventory_log, inventory, product, org RESTART IDENTITY CASCADE");
  }

  // 1. set-replace roundtrip

  @Test
  void setReplace_roundtrips_andMintsChildProducts() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");

    VariantSetView saved =
        variants.replaceVariants(
            org,
            listing.getId(),
            List.of(sizeAxis()),
            List.of(
                variant("m", "SKU-M", "1000001", "199.00"), variant("s", "SKU-S", null, "179.00")));

    assertEquals(1, saved.attributes().size());
    assertEquals("size", saved.attributes().get(0).slug());
    assertEquals("Size", saved.attributes().get(0).names().get("en"));
    assertEquals("المقاس", saved.attributes().get(0).names().get("ar"));
    assertEquals(2, saved.variants().size());

    // The GET echoes what the PUT stated — same keys, options, prices, SKUs, barcodes.
    VariantSetView read = variants.getVariants(org, listing.getId());
    Map<String, VariantRow> byKey = byKey(read);
    assertEquals(Map.of("size", "m"), byKey.get("m").options());
    assertEquals(0, new BigDecimal("199.00").compareTo(byKey.get("m").salesPrice()));
    assertEquals("SKU-M", byKey.get("m").sku());
    assertEquals("1000001", byKey.get("m").barcode());
    assertTrue(byKey.get("m").active());
    assertEquals("SKU-S", byKey.get("s").sku());

    // Each variant minted a child product named "{listing title} — {label}".
    Product childM = products.getById(org, byKey.get("m").productId());
    assertEquals("Shirt — M", childM.getName());
    assertEquals("SKU-M", childM.getSku());
    assertEquals(0, new BigDecimal("199.00").compareTo(childM.getBasePrice()));

    // Re-PUT without "s": it deactivates, and its child product row survives untouched.
    UUID childSId = byKey.get("s").productId();
    variants.replaceVariants(
        org,
        listing.getId(),
        List.of(sizeAxis()),
        List.of(variant("m", "SKU-M", "1000001", "199.00")));

    Map<String, VariantRow> after = byKey(variants.getVariants(org, listing.getId()));
    assertEquals(2, after.size(), "a removed variant is deactivated, never dropped from the read");
    assertTrue(after.get("m").active());
    assertFalse(after.get("s").active());
    assertNotNull(products.getById(org, childSId), "the child product outlives the deactivation");
  }

  // 2. idempotent re-PUT

  @Test
  void identicalRePut_createsNothingNew() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    List<VariantInput> rows =
        List.of(variant("m", "SKU-M", null, "199.00"), variant("s", "SKU-S", null, "199.00"));

    variants.replaceVariants(org, listing.getId(), List.of(sizeAxis()), rows);
    long attributesAfterFirst = count(ATTRIBUTE);
    long valuesAfterFirst = count(ATTRIBUTE_VALUE);
    long variantsAfterFirst = count(PRODUCT_VARIANT);
    long productsAfterFirst = count(PRODUCT);

    variants.replaceVariants(org, listing.getId(), List.of(sizeAxis()), rows);

    assertEquals(attributesAfterFirst, count(ATTRIBUTE));
    assertEquals(valuesAfterFirst, count(ATTRIBUTE_VALUE));
    assertEquals(variantsAfterFirst, count(PRODUCT_VARIANT));
    assertEquals(productsAfterFirst, count(PRODUCT), "no duplicate child products");
    assertEquals(2, variants.getVariants(org, listing.getId()).variants().size());
  }

  @Test
  void axesAreOrgLevel_andSharedAcrossListings() {
    UUID org = createOrg("acme");
    ProductListing shirt = aListing(org, "shirt", "Shirt");
    ProductListing hoodie = aListing(org, "hoodie", "Hoodie");

    variants.replaceVariants(
        org, shirt.getId(), List.of(sizeAxis()), List.of(variant("m", "SH-M", null, "199.00")));
    variants.replaceVariants(
        org, hoodie.getId(), List.of(sizeAxis()), List.of(variant("m", "HO-M", null, "399.00")));

    // One "size" axis (with its two declared values) serves both listings — this is what makes
    // facets a single grouped query rather than a reconciliation of per-listing vocabularies.
    assertEquals(1, count(ATTRIBUTE), "the axis is created once for the org, not once per listing");
    assertEquals(2, count(ATTRIBUTE_VALUE), "its values are shared too, not re-minted per listing");
  }

  // 3. child products are ordinary stock rows

  @Test
  void childProducts_areOrdinaryStockRows() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    variants.replaceVariants(
        org,
        listing.getId(),
        List.of(sizeAxis()),
        List.of(variant("m", "SKU-M", "1000001", "199.00")));
    UUID child = byKey(variants.getVariants(org, listing.getId())).get("m").productId();

    // The existing inventory actions accept the child with no variant-aware code at all.
    inventory.initialise(org, child, 5, actor);
    inventory.restock(org, child, 7, actor);
    assertEquals(12, inventory.getByProductId(org, child).getStockQty());

    // The product-driven stock overview lists it like any other product…
    assertTrue(
        inventory.listOverview(org, null, null, null, 0, 50).rows().stream()
            .anyMatch(r -> child.equals(r.productId())),
        "the child appears in the existing stock overview");

    // …and the scan seam resolves it for free off the V16 partial unique.
    assertEquals(child, products.getByBarcode(org, "1000001").getId());
  }

  // 4. validation + conflicts

  @Test
  void tooManyAttributes_isRejected() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    List<AttributeInput> four =
        List.of(axis("size", "S"), axis("color", "C"), axis("fit", "F"), axis("fabric", "W"));
    assertThrows(
        ValidationException.class,
        () -> variants.replaceVariants(org, listing.getId(), four, List.of()));
  }

  @Test
  void tooManyVariants_isRejected() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    List<ValueInput> values = new ArrayList<>();
    List<VariantInput> rows = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      values.add(new ValueInput("v" + i, Map.of("en", "V" + i)));
      rows.add(
          new VariantInput(
              null, Map.of("size", "v" + i), new BigDecimal("10"), "SKU-" + i, null, true));
    }
    List<AttributeInput> axis =
        List.of(new AttributeInput("size", Map.of("en", "Size"), values.subList(0, 40)));
    assertThrows(
        ValidationException.class,
        () -> variants.replaceVariants(org, listing.getId(), axis, rows));
  }

  @Test
  void tooManyValuesOnOneAttribute_isRejected() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    List<ValueInput> values = new ArrayList<>();
    for (int i = 0; i < 41; i++) {
      values.add(new ValueInput("v" + i, Map.of("en", "V" + i)));
    }
    List<AttributeInput> axis = List.of(new AttributeInput("size", Map.of("en", "Size"), values));
    assertThrows(
        ValidationException.class,
        () -> variants.replaceVariants(org, listing.getId(), axis, List.of()));
  }

  @Test
  void duplicateCombination_missingOption_andNegativePrice_areRejected_withNothingApplied() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");

    // Two rows on the same combination — different keys, same meaning.
    assertThrows(
        ValidationException.class,
        () ->
            variants.replaceVariants(
                org,
                listing.getId(),
                List.of(sizeAxis()),
                List.of(
                    new VariantInput(
                        "a", Map.of("size", "m"), new BigDecimal("10"), "A", null, true),
                    new VariantInput(
                        "b", Map.of("size", "m"), new BigDecimal("10"), "B", null, true))));

    // A row that names no value for a declared axis.
    assertThrows(
        ValidationException.class,
        () ->
            variants.replaceVariants(
                org,
                listing.getId(),
                List.of(sizeAxis(), axis("color", "Color")),
                List.of(
                    new VariantInput(
                        null, Map.of("size", "m"), new BigDecimal("10"), "A", null, true))));

    // A row naming a value that was never declared on the axis.
    assertThrows(
        ValidationException.class,
        () ->
            variants.replaceVariants(
                org,
                listing.getId(),
                List.of(sizeAxis()),
                List.of(
                    new VariantInput(
                        null, Map.of("size", "xxl"), new BigDecimal("10"), "A", null, true))));

    assertThrows(
        ValidationException.class,
        () ->
            variants.replaceVariants(
                org,
                listing.getId(),
                List.of(sizeAxis()),
                List.of(
                    new VariantInput(
                        null, Map.of("size", "m"), new BigDecimal("-1"), "A", null, true))));

    // Every one of those bailed before touching a row.
    assertEquals(0, count(PRODUCT_VARIANT));
    assertEquals(0, count(ATTRIBUTE));
    assertTrue(variants.getVariants(org, listing.getId()).variants().isEmpty());
  }

  @Test
  void newVariantWithoutSku_isRejected() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    assertThrows(
        ValidationException.class,
        () ->
            variants.replaceVariants(
                org,
                listing.getId(),
                List.of(sizeAxis()),
                List.of(variant("m", null, null, "199.00"))));
  }

  @Test
  void duplicateSku_andDuplicateBarcode_are409s_rollingTheWholeSetBack() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    createProduct(org, "TAKEN", "9999999");

    assertThrows(
        ConflictException.class,
        () ->
            variants.replaceVariants(
                org,
                listing.getId(),
                List.of(sizeAxis()),
                List.of(variant("m", "TAKEN", null, "199.00"))));
    assertEquals(0, count(PRODUCT_VARIANT));

    assertThrows(
        ConflictException.class,
        () ->
            variants.replaceVariants(
                org,
                listing.getId(),
                List.of(sizeAxis()),
                List.of(variant("m", "FRESH", "9999999", "199.00"))));
    assertEquals(0, count(PRODUCT_VARIANT));
    // The rollback took the would-be child product with it.
    assertEquals(
        2, count(PRODUCT), "only the parent + the pre-existing conflicting product remain");
  }

  // 5. guards (architecture §5 #11–#12)

  @Test
  void aVariantsChildProduct_cannotGetItsOwnListing() {
    UUID org = createOrg("acme");
    ProductListing listing = aListing(org, "shirt", "Shirt");
    variants.replaceVariants(
        org, listing.getId(), List.of(sizeAxis()), List.of(variant("m", "SKU-M", null, "199.00")));
    UUID child = byKey(variants.getVariants(org, listing.getId())).get("m").productId();

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> listings.create(org, child, "Rogue", null, "rogue", new BigDecimal("1")));
    assertTrue(e.getMessage().toLowerCase().contains("variant"));
  }

  @Test
  void parentDelete_409sWhileVariantsAreActive_thenFallsBackToTodaysRules() {
    UUID org = createOrg("acme");
    UUID parent = createProduct(org, "PARENT", null);
    ProductListing listing =
        listings.create(org, parent, "Shirt", null, "shirt", new BigDecimal("199.00"));
    variants.replaceVariants(
        org, listing.getId(), List.of(sizeAxis()), List.of(variant("m", "SKU-M", null, "199.00")));

    ConflictException live =
        assertThrows(ConflictException.class, () -> products.delete(org, parent));
    assertTrue(live.getMessage().toLowerCase().contains("deactivate"));

    // Deactivate the set: the variant guard steps aside and today's referenced-delete 409 (the
    // listing still points at the parent) is what answers.
    variants.replaceVariants(org, listing.getId(), List.of(sizeAxis()), List.of());
    ConflictException referenced =
        assertThrows(ConflictException.class, () -> products.delete(org, parent));
    assertTrue(referenced.getMessage().contains("referenced by other records"));

    // With the listing gone too, the parent deletes cleanly.
    listings.delete(org, listing.getId());
    products.delete(org, parent);
    assertThrows(NotFoundException.class, () -> products.getById(org, parent));
  }

  // 6. org isolation

  @Test
  void variantsAreInvisibleCrossOrg() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("other");
    ProductListing listing = aListing(orgA, "shirt", "Shirt");
    variants.replaceVariants(
        orgA, listing.getId(), List.of(sizeAxis()), List.of(variant("m", "SKU-M", null, "199.00")));

    // Another org cannot even name the listing, let alone read its variants.
    assertThrows(NotFoundException.class, () -> variants.getVariants(orgB, listing.getId()));
    assertThrows(
        NotFoundException.class,
        () -> variants.replaceVariants(orgB, listing.getId(), List.of(sizeAxis()), List.of()));
    // The same SKU is free in the other org (SKU uniqueness is per-org).
    ProductListing bListing = aListing(orgB, "shirt", "Shirt");
    variants.replaceVariants(
        orgB, bListing.getId(), List.of(sizeAxis()), List.of(variant("m", "SKU-M", null, "10.00")));
    assertEquals(1, variants.getVariants(orgB, bListing.getId()).variants().size());
  }

  // 7. the public read is untouched by this slice

  @Test
  void publicListingRead_isUnchangedByVariants() throws Exception {
    UUID org = createOrg("acme");
    String orgSlug = orgSlug(org);
    ProductListing listing = aListing(org, "shirt", "Shirt");
    listings.publish(org, listing.getId());
    inventory.initialise(org, listing.getProductId(), 3, actor);

    String before = JSON.writeValueAsString(storefront.getListing(orgSlug, "shirt"));

    variants.replaceVariants(
        org,
        listing.getId(),
        List.of(sizeAxis()),
        List.of(variant("m", "SKU-M", null, "249.00"), variant("s", "SKU-S", null, "179.00")));

    // VG1 lands the model and the admin surface only — the storefront learns about variants in VG2.
    assertEquals(before, JSON.writeValueAsString(storefront.getListing(orgSlug, "shirt")));
  }

  // helpers

  private static AttributeInput sizeAxis() {
    return new AttributeInput(
        "size",
        Map.of("en", "Size", "ar", "المقاس"),
        List.of(
            new ValueInput("s", Map.of("en", "S", "ar", "صغير")),
            new ValueInput("m", Map.of("en", "M", "ar", "متوسط"))));
  }

  private static AttributeInput axis(String slug, String name) {
    return new AttributeInput(
        slug, Map.of("en", name), List.of(new ValueInput("m", Map.of("en", "M"))));
  }

  private static VariantInput variant(String size, String sku, String barcode, String price) {
    return new VariantInput(null, Map.of("size", size), new BigDecimal(price), sku, barcode, true);
  }

  private static Map<String, VariantRow> byKey(VariantSetView view) {
    Map<String, VariantRow> byKey = new LinkedHashMap<>();
    view.variants().forEach(v -> byKey.put(v.key(), v));
    return byKey;
  }

  private long count(org.jooq.Table<?> table) {
    return dsl.fetchCount(table);
  }

  private ProductListing aListing(UUID org, String slug, String title) {
    return listings.create(
        org,
        createProduct(org, slug.toUpperCase() + "-P", null),
        title,
        null,
        slug,
        new BigDecimal("199.00"));
  }

  private UUID createOrg(String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, name)
        .set(ORG.SLUG, name + "-" + id)
        .set(ORG.DEFAULT_LOCALE, "en")
        .execute();
    return id;
  }

  private String orgSlug(UUID org) {
    return dsl.select(ORG.SLUG).from(ORG).where(ORG.ID.eq(org)).fetchOne(ORG.SLUG);
  }

  private UUID createProduct(UUID org, String sku, String barcode) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku)
        .set(PRODUCT.SKU, sku)
        .set(PRODUCT.BARCODE, barcode)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }
}
