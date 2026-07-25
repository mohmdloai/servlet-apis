package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT_LINE;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductVariantService;
import com.loai.inventory.service.ProductVariantService.AttributeInput;
import com.loai.inventory.service.ProductVariantService.ValueInput;
import com.loai.inventory.service.ProductVariantService.VariantInput;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.AvailabilityView;
import com.loai.inventory.service.StorefrontService.CheckoutInput;
import com.loai.inventory.service.StorefrontService.CheckoutLine;
import com.loai.inventory.service.StorefrontService.CheckoutResult;
import com.loai.inventory.service.StorefrontService.ListingView;
import com.loai.inventory.service.StorefrontService.StorefrontOutOfStockException;
import com.loai.inventory.service.StorefrontService.VariantView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
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
 * Slice VG2 ({@code stories/catalog_variants_commerce.md}): variants made <b>sellable</b> — the
 * whitelisted public block, the variant-aware checkout/availability/shortage wire, and every
 * listing↔product 1:1 assumption the investigation catalogued (architecture §5).
 *
 * <p>The claim these tests exist to defend is that a variant line behaves like an ordinary line
 * everywhere downstream: it reserves the child product's own stock, snapshots the variant's own
 * price and a composed description, and still resolves back to its listing for the portal order
 * page, the review gate, reorder, and best-sellers — while the only thing that ever crosses the
 * public boundary is the {@code variant_key}.
 */
@Testcontainers
class VariantCommerceIT {

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
  static CustomerPortalService portal;
  static ListingReviewService reviews;
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

    ReservationService reservationService =
        new ReservationService(
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl());
    InvoiceService invoiceService =
        new InvoiceService(
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    RefundService refundService =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl());
    FulfillmentService fulfillmentService =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            invoiceService,
            refundService,
            reservationService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    MagicLinkService magicLink =
        new MagicLinkService(
            dsl,
            new CustomerMagicTokenRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofDays(30));
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            magicLink);
    SalesOrderService salesOrders =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            magicLink,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate());

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
            storage,
            salesOrders,
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
            storage);
    portal =
        new CustomerPortalService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new CustomerAddressRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new FulfillmentRepositoryFactoryImpl(),
            salesOrders);
    reviews =
        new ListingReviewService(
            dsl,
            new ListingReviewRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl());
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
            + " attribute_value, attribute_translation, attribute, payment_allocation,"
            + " sales_invoice_line, sales_invoice, refund, payment, payment_transaction,"
            + " fulfillment_line, fulfillment, inventory_reservation, inventory_log, inventory,"
            + " notification, customer_magic_token, listing_review, sales_order_line, sales_order,"
            + " product_listing_image, product_listing_category, product_listing_translation,"
            + " product_listing, category, product, customer_address, customer, org,"
            + " order_number_counter RESTART IDENTITY CASCADE");
  }

  // ═════════ 1 · the public surface ═════════

  @Test
  void detail_carriesTheVariantsBlock_activeOnly_withPerVariantPriceAndStock() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 4);
    // "s" exists but has no stock; "l" is deactivated below.
    variants.replaceVariants(
        shop.org,
        shop.listingId,
        List.of(sizeAxis("s", "m", "l")),
        List.of(row("m", "SH-M", "249.00"), row("s", "SH-S", "199.00")));

    ListingView detail = storefront.getListing(shop.orgSlug, "shirt");
    assertTrue(detail.hasVariants());
    assertEquals(
        List.of("m", "s"),
        detail.variants().stream().map(VariantView::key).toList(),
        "curated order (sort_order), active only — the deactivated 'l' is gone");

    VariantView m = variantOf(detail, "m");
    assertEquals(
        0, new BigDecimal("249.00").compareTo(m.price()), "its OWN price, not the listing's");
    assertTrue(m.inStock());
    assertEquals("M", m.label());
    assertEquals(Map.of("size", "M"), m.options(), "options carry localized labels, not slugs");
    assertFalse(variantOf(detail, "s").inStock(), "per-variant stock, not the listing's");
    assertTrue(detail.inStock(), "listing-level in_stock is the union — 'm' has stock");
  }

  @Test
  void listRow_showsTheMinVariantPrice_andHasVariants() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("s"), 2);

    StorefrontService.ListingPage page = storefront.listPublished(shop.orgSlug, null, 0, 20);
    ListingView row = rowOf(page, "shirt");
    assertTrue(row.hasVariants());
    // The listing's own sales_price is 199; the cheapest ACTIVE variant is 179 — the honest "from".
    assertEquals(0, new BigDecimal("179.00").compareTo(row.salesPrice()));
    assertTrue(row.inStock(), "any active variant in stock ⇒ the row is in stock");
    assertTrue(row.variants().isEmpty(), "a grid row never pays for the variants block");

    // A variant-less listing in the same org is untouched by any of it.
    ListingView plain = rowOf(page, "mug");
    assertFalse(plain.hasVariants());
    assertEquals(0, new BigDecimal("50.00").compareTo(plain.salesPrice()));
  }

  @Test
  void draftListing_variantsAreUnreachable() {
    Shop shop = shopWithShirt();
    listings.unpublish(shop.org, shop.listingId);
    assertThrows(NotFoundException.class, () -> storefront.getListing(shop.orgSlug, "shirt"));
    // …and the availability batch answers the opaque false, never an oracle.
    assertFalse(availability(shop, "shirt::m").get(0).inStock());
  }

  @Test
  void whitelist_theVariantsBlockLeaksNoInternalIdentity() throws Exception {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 3);

    String json =
        JSON.writeValueAsString(
            PublicListingResponse.from(storefront.getListing(shop.orgSlug, "shirt")));

    assertTrue(json.contains("\"has_variants\":true"), json);
    assertTrue(json.contains("\"key\":\"m\""), json);
    assertTrue(json.contains("\"label\":\"M\""), json);
    // The pinned no-leak set, extended for VG2: a variant is named by its key and NOTHING else.
    for (String forbidden :
        List.of("product_id", "\"sku\"", "SH-M", "barcode", "\"id\"", "sort_order", "quantity")) {
      assertFalse(json.contains(forbidden), "leaked " + forbidden + " in " + json);
    }
  }

  // ═════════ 2 · availability ═════════

  @Test
  void availability_listingTokenIsAnyVariant_variantTokenIsThatVariant() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 5); // only M has stock

    List<AvailabilityView> rows = availability(shop, "shirt", "shirt::m", "shirt::s", "mug");
    assertEquals(
        List.of("shirt", "shirt::m", "shirt::s", "mug"),
        rows.stream().map(AvailabilityView::slug).toList(),
        "one row per requested token, echoed verbatim, in request order");
    assertTrue(rows.get(0).inStock(), "listing-level: SOME variant is buyable");
    assertTrue(rows.get(1).inStock());
    assertFalse(rows.get(2).inStock(), "variant-level: this one is not");
    assertFalse(rows.get(3).inStock(), "the variant-less mug is untracked");
  }

  @Test
  void availability_unknownOrDeactivatedVariantKey_isOpaqueFalse() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 5);
    // Deactivate M by set-replacing it away; its stock is untouched but it is no longer for sale.
    variants.replaceVariants(
        shop.org, shop.listingId, List.of(sizeAxis("s", "m")), List.of(row("s", "SH-S", "179.00")));

    List<AvailabilityView> rows = availability(shop, "shirt::m", "shirt::nope");
    assertFalse(rows.get(0).inStock(), "a deactivated variant is not buyable, however much stock");
    assertFalse(
        rows.get(1).inStock(), "an unknown key is the same opaque false as an unknown slug");
  }

  // ═════════ 3 · checkout happy path ═════════

  @Test
  void anonymousCheckout_reservesTheChildsStock_andSnapshotsTheVariantPriceAndTitle() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 5);
    stock(shop.org, shop.parent, 99); // the parent's own stock must NOT be touched

    CheckoutResult result =
        storefront.checkout(
            shop.orgSlug, cart(line("shirt", "m", 2)), UUID.randomUUID().toString());

    assertTrue(result.created());
    assertEquals(OrderStatus.PENDING_PAYMENT, result.order().getStatus());
    SalesOrderLine line = result.lines().get(0);
    assertEquals(shop.child("m"), line.getProductId(), "the CHILD product is what was ordered");
    assertEquals(0, new BigDecimal("249.00").compareTo(line.getUnitPrice()), "the variant's price");
    assertEquals("Shirt — M", line.getDescription(), "the composed title is frozen onto the line");
    assertEquals(3, available(shop.org, shop.child("m")), "5 - 2 reserved on the child");
    assertEquals(99, available(shop.org, shop.parent), "the parent's stock is untouched");
  }

  @Test
  void twoVariantsOfOneListing_areTwoIndependentLines() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 5);
    stock(shop.org, shop.child("s"), 5);

    CheckoutResult result =
        storefront.checkout(
            shop.orgSlug,
            cart(line("shirt", "m", 2), line("shirt", "s", 1)),
            UUID.randomUUID().toString());

    assertEquals(2, result.lines().size());
    assertEquals(
        List.of("Shirt — M", "Shirt — S"),
        result.lines().stream().map(SalesOrderLine::getDescription).toList());
    assertEquals(3, available(shop.org, shop.child("m")));
    assertEquals(4, available(shop.org, shop.child("s")));
  }

  @Test
  void portalCheckout_takesTheSameVariantWire() {
    Shop shop = shopWithShirt();
    UUID customer = createCustomer(shop.org, "nadia@example.com");
    stock(shop.org, shop.child("m"), 5);

    CustomerPortalService.CheckoutInput input =
        new CustomerPortalService.CheckoutInput(
            List.of(new CustomerPortalService.CheckoutLine("shirt", "m", 2)),
            null,
            null,
            new CustomerPortalService.AddressInput(null, "Nadia", "+20100", "1 Nile St", false),
            false);
    CheckoutResult result =
        portal.checkout(shop.org, customer, input, UUID.randomUUID().toString());

    assertEquals(shop.child("m"), result.lines().get(0).getProductId());
    assertEquals("Shirt — M", result.lines().get(0).getDescription());
    assertEquals(3, available(shop.org, shop.child("m")));
  }

  // ═════════ 4 · checkout rules ═════════

  @Test
  void aHasVariantsListingWithNoVariantOnTheLine_is400_namingTheSlug() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.parent, 50);
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                storefront.checkout(
                    shop.orgSlug, cart(line("shirt", null, 1)), UUID.randomUUID().toString()));
    assertTrue(e.getMessage().contains("variant is required for shirt"), e.getMessage());
    assertEquals(50, available(shop.org, shop.parent), "nothing was reserved");
  }

  @Test
  void aVariantOnAVariantLessListing_is400() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.mugProduct, 5);
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                storefront.checkout(
                    shop.orgSlug, cart(line("mug", "m", 1)), UUID.randomUUID().toString()));
    assertTrue(e.getMessage().contains("mug has no variants"), e.getMessage());
  }

  @Test
  void anUnknownOrDeactivatedVariantKey_isTheSameOpaque404AsAnUnknownSlug() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 5);

    NotFoundException unknown =
        assertThrows(
            NotFoundException.class,
            () ->
                storefront.checkout(
                    shop.orgSlug, cart(line("shirt", "xxl", 1)), UUID.randomUUID().toString()));
    NotFoundException unknownSlug =
        assertThrows(
            NotFoundException.class,
            () ->
                storefront.checkout(
                    shop.orgSlug, cart(line("nope", null, 1)), UUID.randomUUID().toString()));
    assertEquals(
        unknownSlug.getMessage(),
        unknown.getMessage(),
        "identical message — the endpoint is never an oracle for which options exist");

    // Deactivating a variant makes its key answer exactly the same way.
    variants.replaceVariants(
        shop.org, shop.listingId, List.of(sizeAxis("s", "m")), List.of(row("s", "SH-S", "179.00")));
    assertThrows(
        NotFoundException.class,
        () ->
            storefront.checkout(
                shop.orgSlug, cart(line("shirt", "m", 1)), UUID.randomUUID().toString()));
  }

  @Test
  void aVariantShortage_409sWithTheVariantKeyAndComposedTitle() {
    Shop shop = shopWithShirt();
    stock(shop.org, shop.child("m"), 1);
    stock(shop.org, shop.child("s"), 50);

    StorefrontOutOfStockException e =
        assertThrows(
            StorefrontOutOfStockException.class,
            () ->
                storefront.checkout(
                    shop.orgSlug,
                    cart(line("shirt", "m", 5), line("shirt", "s", 1)),
                    UUID.randomUUID().toString()));

    assertEquals(1, e.shortages().size(), "only the short line is reported, not the whole listing");
    StorefrontService.StorefrontShortage shortage = e.shortages().get(0);
    assertEquals("shirt", shortage.listingSlug());
    assertEquals("m", shortage.variant());
    assertEquals("Shirt — M", shortage.title());
    assertEquals(5, shortage.requested());
    assertEquals(1, shortage.available());
  }

  // ═════════ 5 · the downstream seams (architecture §5) ═════════

  @Test
  void portalOrderDetail_resolvesAVariantLineBackToItsListingSlug() {
    Shop shop = shopWithShirt();
    UUID customer = createCustomer(shop.org, "nadia@example.com");
    stock(shop.org, shop.child("m"), 5);
    CheckoutResult placed = placeForCustomer(shop, customer, "m", 1);

    CustomerPortalService.OrderDetail detail =
        portal.getOrderDetail(shop.org, customer, placed.order().getOrderNumber());
    assertEquals(
        "shirt",
        detail.listingSlugByProduct().get(shop.child("m")),
        "the child resolves through the bridge — 'rate this item' keeps working");
  }

  @Test
  void reviewEligibility_isEarnedByDeliveringAnyVariant() {
    Shop shop = shopWithShirt();
    UUID customer = createCustomer(shop.org, "nadia@example.com");
    stock(shop.org, shop.child("m"), 5);
    CheckoutResult placed = placeForCustomer(shop, customer, "m", 1);

    // Before delivery: not eligible (goods not in hand) — the gate is unchanged in that respect.
    assertThrows(
        AuthorizationException.class,
        () -> reviews.submit(shop.org, customer, "shirt", 5, "Great"));

    deliver(shop.org, placed.lines().get(0).getId(), 1);
    ListingReviewService.Submitted submitted =
        reviews.submit(shop.org, customer, "shirt", 5, "Great");
    assertTrue(submitted.created(), "buying ANY variant qualifies you to review the listing");
  }

  @Test
  void reorder_reAddsTheSameVariant_andReportsADeactivatedOneUnavailable() {
    Shop shop = shopWithShirt();
    UUID customer = createCustomer(shop.org, "nadia@example.com");
    stock(shop.org, shop.child("m"), 5);
    stock(shop.org, shop.child("s"), 5);
    CheckoutResult placed = placeForCustomer(shop, customer, "m", 2, "s", 1);

    CustomerPortalService.ReorderResult again =
        portal.reorder(shop.org, customer, placed.order().getOrderNumber());
    assertEquals(2, again.items().size());
    CustomerPortalService.ReorderItem m =
        again.items().stream().filter(i -> "m".equals(i.variant())).findFirst().orElseThrow();
    assertEquals("shirt", m.slug());
    assertEquals("Shirt — M", m.title());
    assertEquals(0, new BigDecimal("249.00").compareTo(m.unitPrice()), "the variant's own price");
    assertTrue(m.inStock());
    assertEquals(2, m.qty());

    // Retire M; a reorder of the same past order must now report it rather than substitute.
    variants.replaceVariants(
        shop.org, shop.listingId, List.of(sizeAxis("s", "m")), List.of(row("s", "SH-S", "179.00")));
    CustomerPortalService.ReorderResult afterRetiring =
        portal.reorder(shop.org, customer, placed.order().getOrderNumber());
    assertEquals(List.of("s"), afterRetiring.items().stream().map(i -> i.variant()).toList());
    assertEquals(1, afterRetiring.unavailable().size());
    assertEquals("Shirt — M", afterRetiring.unavailable().get(0).description());
  }

  @Test
  void bestSellers_ranksTheParentListingByItsChildrensSales() {
    Shop shop = shopWithShirt();
    UUID customer = createCustomer(shop.org, "nadia@example.com");
    stock(shop.org, shop.child("m"), 50);
    stock(shop.org, shop.child("s"), 50);
    stock(shop.org, shop.mugProduct, 50);

    // The mug sells 5 as a single product; the shirt sells 4 + 3 across two variants.
    sell(shop, shop.mugProduct, 5);
    sell(shop, shop.child("m"), 4);
    sell(shop, shop.child("s"), 3);

    List<String> ranked =
        storefront
            .listPublished(
                shop.orgSlug, null, null, null, null, "best_selling", null, null, null, 0, 20)
            .items()
            .stream()
            .map(ListingView::slug)
            .toList();
    assertEquals(
        List.of("shirt", "mug"),
        ranked,
        "7 units across the shirt's variants out-ranks the mug's 5 — the listing is the unit");

    // ?sold=true includes the listing whose sales were all through children.
    List<String> sold =
        storefront
            .listPublished(shop.orgSlug, null, null, null, null, null, null, "true", null, 0, 20)
            .items()
            .stream()
            .map(ListingView::slug)
            .toList();
    assertTrue(sold.contains("shirt"), "a listing that sold only through variants still counts");
    assertEquals(2, sold.size());
  }

  // ═════════ helpers ═════════

  /** A seeded shop: a "shirt" listing with S/M variants, plus a variant-less "mug". */
  private record Shop(
      UUID org,
      String orgSlug,
      UUID listingId,
      UUID parent,
      UUID mugProduct,
      Map<String, UUID> childrenByKey) {
    UUID child(String key) {
      return childrenByKey.get(key);
    }
  }

  private Shop shopWithShirt() {
    UUID org = createOrg("acme");
    UUID parent = createProduct(org, "SHIRT");
    ProductListing shirt =
        listings.create(org, parent, "Shirt", null, "shirt", new BigDecimal("199.00"));
    listings.publish(org, shirt.getId());

    UUID mugProduct = createProduct(org, "MUG");
    ProductListing mug =
        listings.create(org, mugProduct, "Mug", null, "mug", new BigDecimal("50.00"));
    listings.publish(org, mug.getId());

    variants.replaceVariants(
        org,
        shirt.getId(),
        List.of(sizeAxis("s", "m")),
        List.of(row("m", "SH-M", "249.00"), row("s", "SH-S", "179.00")));

    Map<String, UUID> children = new java.util.HashMap<>();
    variants
        .getVariants(org, shirt.getId())
        .variants()
        .forEach(v -> children.put(v.key(), v.productId()));
    return new Shop(org, orgSlug(org), shirt.getId(), parent, mugProduct, children);
  }

  private static AttributeInput sizeAxis(String... valueSlugs) {
    List<ValueInput> values =
        java.util.Arrays.stream(valueSlugs)
            .map(
                slug ->
                    new ValueInput(
                        slug,
                        Map.of(
                            "en", slug.toUpperCase(java.util.Locale.ROOT), "ar", "مقاس " + slug)))
            .toList();
    return new AttributeInput("size", Map.of("en", "Size", "ar", "المقاس"), values);
  }

  private static VariantInput row(String size, String sku, String price) {
    return new VariantInput(null, Map.of("size", size), new BigDecimal(price), sku, null, true);
  }

  private static CheckoutLine line(String slug, String variantKey, int qty) {
    return new CheckoutLine(slug, variantKey, qty);
  }

  private static CheckoutInput cart(CheckoutLine... lines) {
    return new CheckoutInput(
        new SalesOrderService.CustomerInput(
            "Nadia", "nadia@example.com", "+20100000000", "1 Nile St"),
        List.of(lines),
        null);
  }

  private List<AvailabilityView> availability(Shop shop, String... tokens) {
    return storefront.availability(shop.orgSlug, List.of(tokens));
  }

  private static VariantView variantOf(ListingView detail, String key) {
    return detail.variants().stream()
        .filter(v -> v.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no variant " + key));
  }

  private static ListingView rowOf(StorefrontService.ListingPage page, String slug) {
    return page.items().stream()
        .filter(v -> v.slug().equals(slug))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row " + slug));
  }

  private CheckoutResult placeForCustomer(Shop shop, UUID customer, String key, int qty) {
    return placeForCustomer(shop, customer, key, qty, null, 0);
  }

  private CheckoutResult placeForCustomer(
      Shop shop, UUID customer, String key, int qty, String key2, int qty2) {
    List<CustomerPortalService.CheckoutLine> lines =
        key2 == null
            ? List.of(new CustomerPortalService.CheckoutLine("shirt", key, qty))
            : List.of(
                new CustomerPortalService.CheckoutLine("shirt", key, qty),
                new CustomerPortalService.CheckoutLine("shirt", key2, qty2));
    return portal.checkout(
        shop.org,
        customer,
        new CustomerPortalService.CheckoutInput(
            lines,
            null,
            null,
            new CustomerPortalService.AddressInput(null, "Nadia", "+20100", "1 Nile St", false),
            false),
        UUID.randomUUID().toString());
  }

  /** A DELIVERED fulfillment line — the review gate's and the order detail's precondition. */
  private void deliver(UUID org, UUID salesOrderLineId, int qty) {
    UUID orderId =
        dsl.select(SALES_ORDER_LINE.SALES_ORDER_ID)
            .from(SALES_ORDER_LINE)
            .where(SALES_ORDER_LINE.ID.eq(salesOrderLineId))
            .fetchSingle()
            .value1();
    UUID fulfillmentId = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillmentId)
        .set(FULFILLMENT.ORG_ID, org)
        .set(FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(
            FULFILLMENT.STATUS,
            com.loai.inventory.repository.generated.enums.FulfillmentStatus.DELIVERED)
        .execute();
    dsl.insertInto(FULFILLMENT_LINE)
        .set(FULFILLMENT_LINE.ID, UUID.randomUUID())
        .set(FULFILLMENT_LINE.FULFILLMENT_ID, fulfillmentId)
        .set(FULFILLMENT_LINE.SALES_ORDER_LINE_ID, salesOrderLineId)
        .set(FULFILLMENT_LINE.QUANTITY, qty)
        .execute();
  }

  /**
   * A money-committed sale of {@code qty} units of one product — the best-sellers window's input.
   */
  private void sell(Shop shop, UUID productId, int qty) {
    UUID orderId = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, shop.org)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-" + UUID.randomUUID().toString().substring(0, 8))
        .set(SALES_ORDER.STATUS, com.loai.inventory.repository.generated.enums.OrderStatus.PAID)
        .set(SALES_ORDER.CHANNEL, com.loai.inventory.repository.generated.enums.OrderChannel.ONLINE)
        .set(SALES_ORDER.PLACED_AT, java.time.OffsetDateTime.now().minusDays(1))
        .set(SALES_ORDER.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
        .execute();
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, UUID.randomUUID())
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, productId)
        .set(SALES_ORDER_LINE.DESCRIPTION, "sold")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, new BigDecimal("10.00").multiply(new BigDecimal(qty)))
        .set(SALES_ORDER_LINE.LINE_TOTAL, new BigDecimal("10.00").multiply(new BigDecimal(qty)))
        .execute();
  }

  private void stock(UUID org, UUID productId, int qty) {
    inventory.initialise(org, productId, qty, actor);
  }

  private int available(UUID org, UUID productId) {
    Integer v =
        dsl.select(INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY))
            .from(INVENTORY)
            .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(productId)))
            .fetchOne(0, Integer.class);
    return v == null ? 0 : v;
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

  private UUID createCustomer(UUID org, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.CUSTOMER)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.ID, id)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.ORG_ID, org)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.NAME, "Nadia")
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.EMAIL, email)
        .execute();
    return id;
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
  void seededShopIsWellFormed() {
    Shop shop = shopWithShirt();
    assertNotNull(shop.child("m"));
    assertNotNull(shop.child("s"));
    assertFalse(shop.child("m").equals(shop.parent), "a variant is its own child product");
  }
}
