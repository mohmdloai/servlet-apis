package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER_ADDRESS;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
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
import com.loai.inventory.domain.model.CustomerAddress;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
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
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.CustomerPortalService.AddressInput;
import com.loai.inventory.service.CustomerPortalService.CheckoutInput;
import com.loai.inventory.service.CustomerPortalService.CheckoutLine;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.StorefrontService.CheckoutResult;
import com.loai.inventory.service.StorefrontService.StorefrontOutOfStockException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
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
 * Slice P6 ({@code stories/portal_checkout.md}): the authenticated checkout behind {@code POST
 * /api/portal/checkout}, driven through {@link CustomerPortalService#checkout} against the real
 * placement engine (no Tomcat). Covers AC 1 (the order is bound to the session customer and shows
 * in the portal order history), AC 2 (saved-address ownership + save-to-book + XOR validation), AC
 * 3 (same commerce semantics as anon: opaque 404, slug-keyed 409, idempotent replay), and AC 4
 * (no-leak scan + portal {@code track_url}). AC 5 — the anonymous checkout unchanged — is the
 * existing {@code PublicCheckoutIT}, which this slice does not touch.
 */
@Testcontainers
class PortalCheckoutIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CustomerPortalService portal;
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
            reservationService);
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
            magicLink);
    portal =
        new CustomerPortalService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new CustomerAddressRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl(),
            salesOrders);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, refund, payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, notification, customer_magic_token, sales_order_line,"
            + " sales_order, product_listing_image, product_listing_category,"
            + " product_listing, category, product, customer_address, customer, org,"
            + " order_number_counter RESTART IDENTITY CASCADE");
  }

  // ── AC 1: the order is the session customer's by construction ────────────────

  @Test
  void checkout_bindsOrderToSessionCustomer_andShowsInPortalOrders() {
    UUID org = createOrg("acme", "Pay via InstaPay to acme@instapay.");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "GOPRO", new BigDecimal("999.00"));
    publishListing(org, product, "gopro-hero3", "GoPro Hero3+", new BigDecimal("759.50"));
    createInventory(org, product, 5);

    CheckoutResult r =
        portal.checkout(org, cust, typedCart("gopro-hero3", 2, "12 Nile St, Cairo"), key());

    assertTrue(r.created());
    assertEquals(OrderStatus.PENDING_PAYMENT, r.order().getStatus());
    assertEquals(cust, r.order().getCustomerId(), "bound to the session customer, not an email");
    assertEquals(0, new BigDecimal("759.50").compareTo(r.lines().get(0).getUnitPrice()));
    assertNotNull(r.order().getExpiresAt(), "reserved order carries the per-org TTL");
    assertEquals(3, availableOf(org, product), "stock reserved: 5 - 2 = 3");
    assertTrue(r.paymentInstructions().contains("InstaPay"));

    // It appears in the customer's own history (GET /api/portal/orders).
    CustomerPortalService.OrderPage history = portal.listOrders(org, cust, 0, 20);
    assertEquals(1, history.total());
    assertEquals(r.order().getOrderNumber(), history.items().get(0).order().getOrderNumber());

    // The typed delivery contact is frozen onto the CRM row (the codebase's checkout snapshot).
    assertEquals("12 Nile St, Cairo", customerAddressOf(cust));
  }

  @Test
  void checkout_neverReadsAnIdentityFromTheBody_customerEmailUntouched() {
    UUID org = createOrg("acme", "pay");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    portal.checkout(org, cust, typedCart("widget", 1, "1 Nile St"), key());

    String email =
        dsl.select(CUSTOMER.EMAIL)
            .from(CUSTOMER)
            .where(CUSTOMER.ID.eq(cust))
            .fetchOne(0, String.class);
    assertEquals("nadia@example.com", email, "the session identity is fixed — no email crosses");
    assertEquals(
        1,
        dsl.fetchCount(SALES_ORDER, SALES_ORDER.CUSTOMER_ID.eq(cust)),
        "exactly one order, attributed by id");
  }

  // ── AC 2: saved address vs typed address ─────────────────────────────────────

  @Test
  void savedAddressId_usesItsSnapshot_andForeignOrUnknownIdIsOpaque404() {
    UUID org = createOrg("acme", "pay");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID other = createCustomer(org, "other@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    CustomerAddress home =
        portal.createAddress(
            org, cust, new AddressInput("Home", "Nadia", "+20100", "9 Saved St, Giza", false));

    CheckoutResult r =
        portal.checkout(
            org,
            cust,
            new CheckoutInput(
                List.of(new CheckoutLine("widget", 1)), null, home.getId(), null, false),
            key());
    assertTrue(r.created());
    assertEquals("9 Saved St, Giza", customerAddressOf(cust), "the saved snapshot was used");

    // The other customer cannot consume Nadia's book row — the same opaque 404 as P4.
    assertThrows(
        NotFoundException.class,
        () ->
            portal.checkout(
                org,
                other,
                new CheckoutInput(
                    List.of(new CheckoutLine("widget", 1)), null, home.getId(), null, false),
                key()));
    // Unknown id → the same 404.
    assertThrows(
        NotFoundException.class,
        () ->
            portal.checkout(
                org,
                cust,
                new CheckoutInput(
                    List.of(new CheckoutLine("widget", 1)), null, UUID.randomUUID(), null, false),
                key()));
  }

  @Test
  void exactlyOneOfAddressIdOrTypedAddress_else400() {
    UUID org = createOrg("acme", "pay");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    List<CheckoutLine> lines = List.of(new CheckoutLine("widget", 1));
    // Neither.
    assertThrows(
        ValidationException.class,
        () -> portal.checkout(org, cust, new CheckoutInput(lines, null, null, null, false), key()));
    // Both.
    assertThrows(
        ValidationException.class,
        () ->
            portal.checkout(
                org,
                cust,
                new CheckoutInput(
                    lines,
                    null,
                    UUID.randomUUID(),
                    new AddressInput(null, null, null, "1 St", false),
                    false),
                key()));
    // save_address without a typed address.
    assertThrows(
        ValidationException.class,
        () ->
            portal.checkout(
                org, cust, new CheckoutInput(lines, null, UUID.randomUUID(), null, true), key()));
    // Typed address with a blank address line.
    assertThrows(
        ValidationException.class,
        () ->
            portal.checkout(
                org,
                cust,
                new CheckoutInput(
                    lines, null, null, new AddressInput(null, null, null, "  ", false), false),
                key()));
  }

  @Test
  void saveAddress_addsExactlyOneBookRow_replayDoesNotDuplicate_shortageRollsItBack() {
    UUID org = createOrg("acme", "pay");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    CheckoutInput input =
        new CheckoutInput(
            List.of(new CheckoutLine("widget", 1)),
            null,
            null,
            new AddressInput("New flat", "Nadia", "+20100", "5 New St, Cairo", false),
            true);
    String idem = key();
    CheckoutResult first = portal.checkout(org, cust, input, idem);
    assertTrue(first.created());
    assertEquals(1, bookRowCount(cust), "save_address adds exactly one book row");
    assertTrue(
        portal.listAddresses(org, cust).get(0).isDefault(),
        "the customer's first saved address becomes their default");

    // Idempotent replay: same order back, still one book row.
    CheckoutResult replay = portal.checkout(org, cust, input, idem);
    assertFalse(replay.created());
    assertEquals(first.order().getOrderNumber(), replay.order().getOrderNumber());
    assertEquals(1, bookRowCount(cust), "a replayed checkout never re-adds the address");

    // A shortage rolls the whole txn back — including the would-be book row.
    CheckoutInput shortage =
        new CheckoutInput(
            List.of(new CheckoutLine("widget", 99)),
            null,
            null,
            new AddressInput("Work", "Nadia", "+20100", "7 Work St", false),
            true);
    assertThrows(
        StorefrontOutOfStockException.class, () -> portal.checkout(org, cust, shortage, key()));
    assertEquals(1, bookRowCount(cust), "a failed placement saves nothing");
  }

  // ── AC 3: same commerce semantics as the anonymous checkout ──────────────────

  @Test
  void unknownOrDraftSlug_isOpaque404() {
    UUID org = createOrg("acme", "pay");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    insertListing(org, product, "wip", "WIP", new BigDecimal("12.00"), ListingStatus.DRAFT);
    createInventory(org, product, 5);

    assertThrows(
        NotFoundException.class, () -> portal.checkout(org, cust, typedCartFor("wip", 1), key()));
    assertThrows(
        NotFoundException.class, () -> portal.checkout(org, cust, typedCartFor("ghost", 1), key()));
  }

  @Test
  void shortage_is409_slugKeyed_noProductId() throws Exception {
    UUID org = createOrg("acme", "pay");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 1);

    StorefrontOutOfStockException ex =
        assertThrows(
            StorefrontOutOfStockException.class,
            () -> portal.checkout(org, cust, typedCartFor("widget", 3), key()));
    assertEquals(1, ex.shortages().size());
    assertEquals("widget", ex.shortages().get(0).listingSlug());
    assertEquals("Widget", ex.shortages().get(0).title());
    assertEquals(3, ex.shortages().get(0).requested());
    assertEquals(1, ex.shortages().get(0).available());
    String json = JSON.writeValueAsString(ex.shortages());
    assertFalse(json.toLowerCase().contains("product_id"));
    assertFalse(json.contains(product.toString()));
  }

  @Test
  void duplicateIdempotencyKey_replaysTheOrder_noSecondReservation() {
    UUID org = createOrg("acme", "pay");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("10.00"));
    publishListing(org, product, "widget", "Widget", new BigDecimal("12.00"));
    createInventory(org, product, 5);

    String idem = key();
    CheckoutResult first = portal.checkout(org, cust, typedCart("widget", 2, "1 Nile St"), idem);
    CheckoutResult second = portal.checkout(org, cust, typedCart("widget", 2, "1 Nile St"), idem);

    assertTrue(first.created());
    assertFalse(second.created(), "replay is not a fresh order (→ 200)");
    assertEquals(first.order().getOrderNumber(), second.order().getOrderNumber());
    assertEquals(3, availableOf(org, product), "only the first reservation happened");
    assertNotNull(second.trackUrl(), "the portal track_url is reconstructable even on replay");
  }

  // ── AC 4: customer-safe response + portal track_url ─────────────────────────

  @Test
  void responseJson_carriesNoInternalIds_andTrackUrlIsThePortalOrderPage() throws Exception {
    UUID org = createOrg("acme", "Pay via InstaPay.");
    UUID cust = createCustomer(org, "nadia@example.com");
    UUID product = createProduct(org, "P", new BigDecimal("999.00"));
    publishListing(org, product, "gopro-hero3", "GoPro Hero3+", new BigDecimal("759.50"));
    createInventory(org, product, 5);

    CheckoutResult r = portal.checkout(org, cust, typedCart("gopro-hero3", 2, "1 Nile St"), key());
    String json = JSON.writeValueAsString(PublicOrderResponse.from(r));

    assertFalse(json.contains("product_id"), json);
    assertFalse(json.contains("org_id"), json);
    assertFalse(json.contains("customer_id"), json);
    assertFalse(json.contains(product.toString()), json);
    assertFalse(json.contains(org.toString()), json);
    assertFalse(json.contains(cust.toString()), json);
    assertFalse(json.contains(r.order().getId().toString()), json);
    assertTrue(json.contains("GoPro Hero3+"), json);

    // The portal order page, not the anonymous magic link (org default_locale ar → /ar/...).
    assertEquals(
        "/ar/acme/account/orders/" + r.order().getOrderNumber(),
        r.trackUrl(),
        "track_url points at the portal order detail page");
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static CheckoutInput typedCart(String slug, int qty, String address) {
    return new CheckoutInput(
        List.of(new CheckoutLine(slug, qty)),
        "optional note",
        null,
        new AddressInput(null, "Nadia", "+20 100 000 0000", address, false),
        false);
  }

  private static CheckoutInput typedCartFor(String slug, int qty) {
    return new CheckoutInput(
        List.of(new CheckoutLine(slug, qty)),
        null,
        null,
        new AddressInput(null, "Nadia", "+20100", "1 Nile St", false),
        false);
  }

  private String customerAddressOf(UUID cust) {
    return dsl.select(CUSTOMER.ADDRESS)
        .from(CUSTOMER)
        .where(CUSTOMER.ID.eq(cust))
        .fetchOne(0, String.class);
  }

  private int bookRowCount(UUID cust) {
    return dsl.fetchCount(CUSTOMER_ADDRESS, CUSTOMER_ADDRESS.CUSTOMER_ID.eq(cust));
  }

  private int availableOf(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY))
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(0, Integer.class);
  }

  private UUID createOrg(String slug, String paymentInstructions) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .set(ORG.DEFAULT_LOCALE, "ar")
        .set(ORG.PAYMENT_INSTRUCTIONS, paymentInstructions)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, email)
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
    // L6: the title lives in the per-language translation table (both locales the same here).
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
