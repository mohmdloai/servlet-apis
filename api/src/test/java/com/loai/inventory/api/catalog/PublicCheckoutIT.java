package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicOrderResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.CheckoutInput;
import com.loai.inventory.service.StorefrontService.CheckoutLine;
import com.loai.inventory.service.StorefrontService.CheckoutResult;
import com.loai.inventory.service.StorefrontService.StorefrontOutOfStockException;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * The keystone slice ({@code stories/public_checkout.md}, B5): an anonymous cart of PUBLISHED slugs
 * becomes a PENDING_PAYMENT order priced from {@code sales_price}, with reservations, a per-org
 * TTL, payment instructions, and an order-view {@code track_url} — with no internal id ever
 * leaking. Drives {@link StorefrontService#checkout} directly against the real placement engine (no
 * Tomcat).
 */
@Testcontainers
class PublicCheckoutIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService storefront;
  static SalesOrderService salesOrders;
  static MagicLinkService magicLink;
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
    magicLink =
        new MagicLinkService(
            dsl,
            new CustomerMagicTokenRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
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
            magicLink,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    salesOrders =
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
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.CouponService(
                dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    storefront =
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
            storage,
            salesOrders,
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
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, refund, payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, notification, customer_magic_token, sales_order_line,"
            + " sales_order, product_listing_image, product_listing_category,"
            + " product_listing, category, product, customer, org, order_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void publishedCart_placesPendingPaymentOrder_pricedFromSalesPrice() {
    UUID org =
        createOrg("acme", true, "Send via InstaPay to acme@instapay, note your order number.");
    // base_price 999.00 (internal) ≠ sales_price 759.50 (published) — the shopper is charged
    // 759.50.
    UUID product = createProduct(org, "GOPRO", new BigDecimal("999.00"));
    publishListing(org, product, "gopro-hero3", "GoPro Hero3+", new BigDecimal("759.50"));
    createInventory(org, product, 5);

    CheckoutResult r = storefront.checkout("acme", cart(new CheckoutLine("gopro-hero3", 2)), key());

    assertTrue(r.created());
    assertEquals(OrderStatus.PENDING_PAYMENT, r.order().getStatus());
    assertTrue(r.order().getOrderNumber().matches("^SO-\\d{4}-\\d{5}$"));
    assertNotNull(r.order().getExpiresAt(), "reserved order carries expires_at");
    assertEquals(0, new BigDecimal("759.50").compareTo(r.lines().get(0).getUnitPrice()));
    assertEquals(0, new BigDecimal("1519.00").compareTo(r.order().getGrandTotal()));
    // Reservation bumped reserved_qty: available = 5 - 2 = 3.
    assertEquals(3, availableOf(org, product));
    // payment instructions echoed from the org (B1).
    assertTrue(r.paymentInstructions().contains("InstaPay"));
    // track_url is the relative order-view link — the branded storefront status page
    // /{locale}/{slug}/orders/{token}, not the raw JSON endpoint.
    assertNotNull(r.trackUrl());
    assertTrue(r.trackUrl().startsWith("/"), r.trackUrl());
    assertTrue(r.trackUrl().contains("/orders/"), r.trackUrl());
    assertFalse(r.trackUrl().startsWith("/api/"), r.trackUrl());
  }

  @Test
  void trackUrl_resolvesAnonymouslyToTheSameOrder() {
    UUID org = createOrg("acme", true, "pay me");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 3);

    CheckoutResult r = storefront.checkout("acme", cart(new CheckoutLine("widget", 1)), key());
    // The token is the last path segment of /{locale}/{slug}/orders/{token}.
    String token = r.trackUrl().substring(r.trackUrl().lastIndexOf('/') + 1);

    var resolved = magicLink.resolveOrderView(token, OffsetDateTime.now());
    assertTrue(resolved.isPresent(), "track_url token resolves");
    var placed = salesOrders.findPlaced(resolved.get().orgId(), resolved.get().orderId());
    assertTrue(placed.isPresent());
    assertEquals(r.order().getOrderNumber(), placed.get().order().getOrderNumber());
  }

  // ── error paths ─────────────────────────────────────────────────────────────

  @Test
  void draftArchivedOrUnknownSlug_isOpaque404_noProductId() throws Exception {
    UUID org = createOrg("acme", true, "pay");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    draftListing(org, product, "wip-widget", "WIP", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    // DRAFT slug and a nonexistent slug are indistinguishable — both opaque 404.
    assertThrows(
        NotFoundException.class,
        () -> storefront.checkout("acme", cart(new CheckoutLine("wip-widget", 1)), key()));
    assertThrows(
        NotFoundException.class,
        () -> storefront.checkout("acme", cart(new CheckoutLine("nope", 1)), key()));
  }

  @Test
  void insufficientStock_409_slugKeyedShortages_noProductId() throws Exception {
    UUID org = createOrg("acme", true, "pay");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 1);

    StorefrontOutOfStockException ex =
        assertThrows(
            StorefrontOutOfStockException.class,
            () -> storefront.checkout("acme", cart(new CheckoutLine("widget", 3)), key()));
    assertEquals(1, ex.shortages().size());
    assertEquals("widget", ex.shortages().get(0).listingSlug());
    assertEquals("Widget", ex.shortages().get(0).title());
    assertEquals(3, ex.shortages().get(0).requested());
    assertEquals(1, ex.shortages().get(0).available());
    // No product_id anywhere in the serialized shortage.
    String json = JSON.writeValueAsString(ex.shortages());
    assertFalse(json.toLowerCase().contains("product_id"));
    assertFalse(json.contains(product.toString()));
  }

  @Test
  void missingCustomerOrEmptyCart_is400() {
    UUID org = createOrg("acme", true, "pay");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    // Empty cart.
    assertThrows(
        ValidationException.class,
        () -> storefront.checkout("acme", new CheckoutInput(customer(), List.of(), null), key()));
    // Missing email.
    CheckoutInput noEmail =
        new CheckoutInput(
            new SalesOrderService.CustomerInput("Mona", null, null, null),
            List.of(new CheckoutLine("widget", 1)),
            null);
    assertThrows(ValidationException.class, () -> storefront.checkout("acme", noEmail, key()));
  }

  @Test
  void inactiveOrUnknownOrg_is404() {
    createOrg("suspended", false, "pay");
    assertThrows(
        NotFoundException.class,
        () -> storefront.checkout("suspended", cart(new CheckoutLine("x", 1)), key()));
    assertThrows(
        NotFoundException.class,
        () -> storefront.checkout("ghost", cart(new CheckoutLine("x", 1)), key()));
  }

  // ── idempotency + concurrency ────────────────────────────────────────────────

  @Test
  void duplicateIdempotencyKey_replaysOneOrder_secondIs200_noSecondReservation() {
    UUID org = createOrg("acme", true, "pay");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    String idem = key();
    CheckoutResult first = storefront.checkout("acme", cart(new CheckoutLine("widget", 2)), idem);
    CheckoutResult second = storefront.checkout("acme", cart(new CheckoutLine("widget", 2)), idem);

    assertTrue(first.created());
    assertFalse(second.created(), "replay is not a fresh order (→ 200)");
    assertEquals(first.order().getOrderNumber(), second.order().getOrderNumber());
    // Only the first reservation happened: available = 5 - 2 = 3, not 1.
    assertEquals(3, availableOf(org, product));
  }

  @Test
  void twoRacingCheckoutsForLastUnit_oneWins_oneGets409Available0() throws Exception {
    UUID org = createOrg("acme", true, "pay");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 1);

    Callable<Object> attempt =
        () -> {
          try {
            return storefront.checkout("acme", cart(new CheckoutLine("widget", 1)), key());
          } catch (StorefrontOutOfStockException e) {
            return e;
          }
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Object> a = pool.submit(attempt);
    Future<Object> b = pool.submit(attempt);
    Object ra = a.get();
    Object rb = b.get();
    pool.shutdown();

    long wins = List.of(ra, rb).stream().filter(o -> o instanceof CheckoutResult).count();
    long losses =
        List.of(ra, rb).stream().filter(o -> o instanceof StorefrontOutOfStockException).count();
    assertEquals(1, wins, "exactly one 201");
    assertEquals(1, losses, "exactly one 409");
    StorefrontOutOfStockException loss =
        (StorefrontOutOfStockException) (ra instanceof StorefrontOutOfStockException ? ra : rb);
    assertEquals(0, loss.shortages().get(0).available());
    assertEquals(0, availableOf(org, product));
  }

  // ── no-leak scan on the response DTO ─────────────────────────────────────────

  @Test
  void responseJson_carriesNoInternalIds() throws Exception {
    UUID org = createOrg("acme", true, "Send via InstaPay to acme@instapay.");
    UUID product = createProduct(org, "P", new BigDecimal("999.00"));
    publishListing(org, product, "gopro-hero3", "GoPro Hero3+", new BigDecimal("759.50"));
    createInventory(org, product, 5);

    CheckoutResult r = storefront.checkout("acme", cart(new CheckoutLine("gopro-hero3", 2)), key());
    String json = JSON.writeValueAsString(PublicOrderResponse.from(r));

    assertFalse(json.contains("product_id"), json);
    assertFalse(json.contains("org_id"), json);
    assertFalse(json.contains("customer_id"), json);
    assertFalse(json.contains(product.toString()), json);
    assertFalse(json.contains(org.toString()), json);
    assertFalse(json.contains(r.order().getId().toString()), json);
    // The title the shopper saw is present (not the internal product description).
    assertTrue(json.contains("GoPro Hero3+"), json);
    assertTrue(json.contains("PENDING_PAYMENT"), json);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static SalesOrderService.CustomerInput customer() {
    return new SalesOrderService.CustomerInput(
        "Mona Hassan", "mona@example.com", "+20 100 000 0000", "12 Tahrir St, Cairo");
  }

  private static CheckoutInput cart(CheckoutLine... lines) {
    return new CheckoutInput(customer(), List.of(lines), "optional note");
  }

  private int availableOf(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY))
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(0, Integer.class);
  }

  private UUID createOrg(String slug, boolean active, String paymentInstructions) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, active)
        .set(ORG.PAYMENT_INSTRUCTIONS, paymentInstructions)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String sku, BigDecimal basePrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, basePrice)
        .execute();
    return id;
  }

  private void publishListing(UUID org, UUID product, String slug, String title, BigDecimal price) {
    insertListing(org, product, slug, title, price, ListingStatus.PUBLISHED);
  }

  private void draftListing(UUID org, UUID product, String slug, String title, BigDecimal price) {
    insertListing(org, product, slug, title, price, ListingStatus.DRAFT);
  }

  private void insertListing(
      UUID org, UUID product, String slug, String title, BigDecimal price, ListingStatus status) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ID, id)
        .set(PRODUCT_LISTING.ORG_ID, org)
        .set(PRODUCT_LISTING.PRODUCT_ID, product)
        .set(PRODUCT_LISTING.SLUG, slug)
        .set(PRODUCT_LISTING.SALES_PRICE, price)
        .set(PRODUCT_LISTING.STATUS, status)
        .set(PRODUCT_LISTING.PUBLISHED_AT, OffsetDateTime.now())
        .execute();
    // L6: the title lives in the per-language translation table. Same value in both locales here,
    // so
    // the read resolves it whatever the org's default_locale is.
    for (String lang : new String[] {"ar", "en"}) {
      dsl.insertInto(PRODUCT_LISTING_TRANSLATION)
          .set(PRODUCT_LISTING_TRANSLATION.LISTING_ID, id)
          .set(PRODUCT_LISTING_TRANSLATION.LANGUAGE, lang)
          .set(PRODUCT_LISTING_TRANSLATION.TITLE, title)
          .execute();
    }
  }

  private void createInventory(UUID org, UUID product, int stockQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
  }
}
