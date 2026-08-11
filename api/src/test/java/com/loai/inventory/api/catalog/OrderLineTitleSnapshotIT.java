package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_TRANSLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
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
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.service.CustomerPortalService;
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
import com.loai.inventory.service.platform.OrgMilestoneService;
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
 * Content-localization slice L2b — the order line snapshots the <b>locale-resolved listing
 * title</b> at checkout, so a bilingual customer's invoice/receipt/PDF reads in their checkout
 * language, never the internal {@code product.name}. Drives the anonymous ({@link
 * StorefrontService#checkout}) and authenticated ({@link CustomerPortalService#checkout}) placement
 * paths directly against the real engine. Asserts the persisted {@code
 * sales_order_line.description}; invoices/receipts copy that value verbatim at delivery, so the
 * line snapshot is the single source the documents render.
 */
@Testcontainers
class OrderLineTitleSnapshotIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService storefront;
  static CustomerPortalService portal;
  static SalesOrderService salesOrders;
  static ObjectStorage storage;

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
            new StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CollectionRepositoryFactoryImpl(),
            storage,
            salesOrders,
            null);
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
            + " sales_order, product_listing_translation, product_listing_image,"
            + " product_listing_category, product_listing, category_translation, category, product,"
            + " customer_address, customer, org, order_number_counter RESTART IDENTITY CASCADE");
  }

  // ── AC1/AC2: anonymous checkout snapshots the resolved title per locale ────────────────────────
  @Test
  void anonCheckout_snapshotsResolvedTitle_perLocale() {
    UUID org = seedOrg("acme", "ar");
    UUID product = seedProduct(org, "Internal Kettle Name");
    UUID listing = seedListing(org, product, "kettle");
    translate(listing, "ar", "غلاية");
    translate(listing, "en", "Kettle");
    seedInventory(org, product, 20);

    // en checkout → the English title, not the internal product.name.
    UUID enOrder = anonCheckout(org, "en", "kettle").order().getId();
    assertEquals("Kettle", lineDescription(enOrder));

    // ar checkout → the Arabic title.
    UUID arOrder = anonCheckout(org, "ar", "kettle").order().getId();
    assertEquals("غلاية", lineDescription(arOrder));

    // unset locale → org default (ar).
    UUID defOrder = anonCheckout(org, null, "kettle").order().getId();
    assertEquals("غلاية", lineDescription(defOrder));
  }

  // ── AC3: a missing locale row falls back to the default title (never product.name, never null) ─
  @Test
  void anonCheckout_missingLocaleRow_fallsBackToDefaultTitle() {
    UUID org = seedOrg("acme", "ar");
    UUID product = seedProduct(org, "Internal Pen Name");
    UUID listing = seedListing(org, product, "pen");
    translate(listing, "ar", "قلم"); // no en row
    seedInventory(org, product, 5);

    UUID enOrder = anonCheckout(org, "en", "pen").order().getId();
    assertEquals("قلم", lineDescription(enOrder));
  }

  // ── AC4: unsupported checkout locale → 400; online order still snapshots product.name ─────────
  @Test
  void unsupportedLocale_is400() {
    UUID org = seedOrg("acme", "ar");
    UUID product = seedProduct(org, "Internal");
    UUID listing = seedListing(org, product, "kettle");
    translate(listing, "ar", "غلاية");
    seedInventory(org, product, 5);

    assertThrows(ValidationException.class, () -> anonCheckout(org, "fr", "kettle"));
  }

  @Test
  void onlineOrder_stillSnapshotsProductName() {
    UUID org = seedOrg("acme", "ar");
    UUID product = seedProduct(org, "Internal Kettle Name");
    UUID listing = seedListing(org, product, "kettle");
    translate(listing, "ar", "غلاية");
    translate(listing, "en", "Kettle");
    seedInventory(org, product, 5);

    // The merchant-authored online order snapshots the internal product.name (unchanged by L2b).
    SalesOrderService.Placed placed =
        salesOrders.placeOnlineOrder(
            org,
            new SalesOrderService.CustomerInput("Mona", "mona@example.com", null, null),
            List.of(new SalesOrderService.OrderLineInput(product, 1)),
            UUID.randomUUID().toString(),
            null,
            com.loai.inventory.domain.model.ActorContext.system("test"));
    assertEquals("Internal Kettle Name", lineDescription(placed.order().getId()));
  }

  // ── AC5: portal checkout mirrors the anonymous snapshot ────────────────────────────────────────
  @Test
  void portalCheckout_snapshotsResolvedTitle_perLocale() {
    UUID org = seedOrg("acme", "ar");
    UUID product = seedProduct(org, "Internal Kettle Name");
    UUID listing = seedListing(org, product, "kettle");
    translate(listing, "ar", "غلاية");
    translate(listing, "en", "Kettle");
    seedInventory(org, product, 20);
    UUID customer = seedCustomer(org, "nadia@example.com");

    UUID enOrder = portalCheckout(org, customer, "en").order().getId();
    assertEquals("Kettle", lineDescription(enOrder));

    UUID arOrder = portalCheckout(org, customer, "ar").order().getId();
    assertEquals("غلاية", lineDescription(arOrder));
  }

  // ── helpers ───────────────────────────────────────────────────────────────────────────────────

  private CheckoutResult portalCheckout(UUID org, UUID customer, String locale) {
    return portal.checkout(
        org,
        customer,
        new CustomerPortalService.CheckoutInput(
            List.of(new CustomerPortalService.CheckoutLine("kettle", 1)),
            null,
            null,
            new CustomerPortalService.AddressInput(null, "Nadia", null, "1 Nile St", false),
            false,
            locale),
        UUID.randomUUID().toString());
  }

  private CheckoutResult anonCheckout(UUID org, String locale, String slug) {
    return storefront.checkout(
        orgSlug(org),
        new CheckoutInput(
            new SalesOrderService.CustomerInput("Mona", "mona@example.com", null, "Cairo"),
            List.of(new CheckoutLine(slug, 1)),
            null,
            locale),
        UUID.randomUUID().toString());
  }

  private String lineDescription(UUID orderId) {
    return dsl.fetchOne(
            "select description from sales_order_line where sales_order_id = ?", orderId)
        .get(0, String.class);
  }

  private String orgSlug(UUID org) {
    return dsl.select(ORG.SLUG).from(ORG).where(ORG.ID.eq(org)).fetchOne(ORG.SLUG);
  }

  private UUID seedOrg(String slug, String defaultLocale) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .set(ORG.DEFAULT_LOCALE, defaultLocale)
        .set(ORG.PAYMENT_INSTRUCTIONS, "pay me")
        .execute();
    return id;
  }

  private UUID seedProduct(UUID org, String internalName) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, internalName)
        .set(PRODUCT.SKU, "SKU-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("999.00"))
        .execute();
    return id;
  }

  private UUID seedListing(UUID org, UUID product, String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ID, id)
        .set(PRODUCT_LISTING.ORG_ID, org)
        .set(PRODUCT_LISTING.PRODUCT_ID, product)
        .set(PRODUCT_LISTING.SLUG, slug)
        .set(PRODUCT_LISTING.SALES_PRICE, new BigDecimal("50.00"))
        .set(PRODUCT_LISTING.STATUS, ListingStatus.PUBLISHED)
        .set(PRODUCT_LISTING.PUBLISHED_AT, OffsetDateTime.now())
        .execute();
    return id;
  }

  private void translate(UUID listing, String language, String title) {
    dsl.insertInto(PRODUCT_LISTING_TRANSLATION)
        .set(PRODUCT_LISTING_TRANSLATION.LISTING_ID, listing)
        .set(PRODUCT_LISTING_TRANSLATION.LANGUAGE, language)
        .set(PRODUCT_LISTING_TRANSLATION.TITLE, title)
        .execute();
  }

  private void seedInventory(UUID org, UUID product, int qty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, qty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
  }

  private UUID seedCustomer(UUID org, String email) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "insert into customer(id, org_id, name, email) values (?, ?, ?, ?)",
        id,
        org,
        "Nadia",
        email);
    return id;
  }
}
