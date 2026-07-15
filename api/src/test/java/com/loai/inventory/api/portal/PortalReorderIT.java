package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PortalReorderResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ListingStatus;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.CustomerPortalService.ReorderItem;
import com.loai.inventory.service.CustomerPortalService.ReorderResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Portal one-click reorder (slice P4, {@code stories/portal_addresses_reorder.md}) end-to-end
 * against real Postgres, driving {@link CustomerPortalService#reorder}. Covers acceptance criteria
 * 2–4:
 *
 * <ul>
 *   <li>AC2 — an owned order resolves each line whose product is currently PUBLISHED to a buyable
 *       item ({@code listing_slug}, current price, {@code in_stock}); the rest go to {@code
 *       unavailable}.
 *   <li>AC3 — a foreign/unknown order number is an opaque 404; a product with no published listing
 *       is skipped, never an error.
 *   <li>AC4 — no internal id leaks (slugs never {@code product_id}); cross-customer isolation
 *       holds.
 * </ul>
 */
@Testcontainers
class PortalReorderIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CustomerPortalService portalService;
  static SalesOrderRepositoryFactoryImpl salesOrderRepositoryFactory;
  static final ObjectMapper mapper = ObjectMapperProvider.build();

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

    salesOrderRepositoryFactory = new SalesOrderRepositoryFactoryImpl();
    portalService =
        new CustomerPortalService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            salesOrderRepositoryFactory,
            new SalesInvoiceRepositoryFactoryImpl(),
            new CustomerAddressRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            null);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE sales_order_line, sales_order, product_listing, inventory, product, customer,"
            + " org RESTART IDENTITY CASCADE");
  }

  // AC2/AC3: published lines → buyable items (in_stock flag); unpublished → unavailable

  @Test
  void reorderResolvesPublishedLines_andReportsTheRest() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");

    // P1: published + in stock. P2: no listing (delisted). P3: published but out of stock.
    UUID p1 = createProduct(org);
    publishListing(org, p1, "widget-blue", new BigDecimal("15.00"));
    inventory(org, p1, 5, 0);
    UUID p2 = createProduct(org);
    UUID p3 = createProduct(org);
    publishListing(org, p3, "widget-red", new BigDecimal("20.00"));
    inventory(org, p3, 0, 0);

    seedOrder(
        org,
        cust,
        "SO-1001",
        OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC),
        List.of(line(p1, "Blue widget", 2), line(p2, "Gone widget", 1), line(p3, "Red widget", 4)));

    ReorderResult result = portalService.reorder(org, cust, "SO-1001");

    assertEquals(2, result.items().size(), "two lines have a live published listing");
    ReorderItem blue = itemBySlug(result, "widget-blue");
    assertEquals(
        new BigDecimal("15.00"), blue.unitPrice(), "current listing price, not the old one");
    assertEquals(2, blue.qty(), "the original quantity is carried through");
    assertTrue(blue.inStock(), "P1 has stock");

    ReorderItem red = itemBySlug(result, "widget-red");
    assertEquals(4, red.qty());
    assertFalse(
        red.inStock(), "P3 is published but out of stock — still an item, flagged not in stock");

    assertEquals(1, result.unavailable().size(), "the delisted product drops out");
    assertEquals("Gone widget", result.unavailable().get(0).description());
    assertEquals(1, result.unavailable().get(0).qty());
  }

  @Test
  void draftListingIsNotBuyable_reportedUnavailable() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    UUID p = createProduct(org);
    insertListing(org, p, "draft-widget", new BigDecimal("9.00"), ListingStatus.DRAFT);
    inventory(org, p, 10, 0);

    seedOrder(
        org,
        cust,
        "SO-1001",
        OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC),
        List.of(line(p, "Draft widget", 1)));

    ReorderResult result = portalService.reorder(org, cust, "SO-1001");
    assertTrue(result.items().isEmpty(), "a DRAFT listing is not a currently-buyable item");
    assertEquals(1, result.unavailable().size());
  }

  // AC3/AC4: ownership gate is an opaque 404; cross-customer isolation

  @Test
  void foreignOrUnknownOrderIsTheSame404() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");
    seedOrder(
        org,
        custA,
        "SO-1001",
        OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC),
        List.of());

    assertThrows(
        NotFoundException.class,
        () -> portalService.reorder(org, custB, "SO-1001"),
        "B cannot reorder A's order");
    assertThrows(
        NotFoundException.class,
        () -> portalService.reorder(org, custA, "SO-9999"),
        "an unknown number is the same 404");
  }

  @Test
  void reorderIsOrgScoped() {
    UUID orgA = createOrg();
    UUID orgB = createOrg();
    UUID cust = createCustomer(orgA, "a@acme.test");
    seedOrder(
        orgA,
        cust,
        "SO-1001",
        OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC),
        List.of());
    assertThrows(NotFoundException.class, () -> portalService.reorder(orgB, cust, "SO-1001"));
  }

  // AC4: the wire body leaks no internal id, keyed only by listing_slug

  @Test
  void reorderBodyLeaksNoInternalFields() throws Exception {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    UUID p1 = createProduct(org);
    publishListing(org, p1, "widget-blue", new BigDecimal("15.00"));
    inventory(org, p1, 5, 0);
    UUID p2 = createProduct(org);

    seedOrder(
        org,
        cust,
        "SO-1001",
        OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC),
        List.of(line(p1, "Blue widget", 2), line(p2, "Gone widget", 1)));

    String json =
        mapper.writeValueAsString(
            PortalReorderResponse.from(portalService.reorder(org, cust, "SO-1001")));

    assertTrue(json.contains("listing_slug"), "items are keyed by the public slug");
    assertTrue(json.contains("in_stock"), "the availability flag is present");
    for (String forbidden :
        List.of("product_id", "customer_id", "org_id", "sales_order_id", p1.toString())) {
      assertFalse(json.contains(forbidden), "must not leak " + forbidden + " — got " + json);
    }
  }

  // helpers

  private static ReorderItem itemBySlug(ReorderResult r, String slug) {
    return r.items().stream()
        .filter(i -> i.slug().equals(slug))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no item for slug " + slug));
  }

  private record LineSpec(UUID productId, String description, int qty) {}

  private static LineSpec line(UUID productId, String description, int qty) {
    return new LineSpec(productId, description, qty);
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, "store-" + id)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Cust")
        .set(CUSTOMER.EMAIL, email)
        .execute();
    return id;
  }

  private UUID createProduct(UUID orgId) {
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

  private void publishListing(UUID org, UUID product, String slug, BigDecimal price) {
    insertListing(org, product, slug, price, ListingStatus.PUBLISHED);
  }

  private void insertListing(
      UUID org, UUID product, String slug, BigDecimal price, ListingStatus status) {
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ID, UUID.randomUUID())
        .set(PRODUCT_LISTING.ORG_ID, org)
        .set(PRODUCT_LISTING.PRODUCT_ID, product)
        .set(PRODUCT_LISTING.TITLE, slug + " title")
        .set(PRODUCT_LISTING.SLUG, slug)
        .set(PRODUCT_LISTING.SALES_PRICE, price)
        .set(PRODUCT_LISTING.STATUS, status)
        .set(PRODUCT_LISTING.PUBLISHED_AT, OffsetDateTime.now())
        .execute();
  }

  private void inventory(UUID org, UUID product, int stock, int reserved) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stock)
        .set(INVENTORY.RESERVED_QTY, reserved)
        .execute();
  }

  /** Seed a placed order with the given lines via the real repository insert. */
  private void seedOrder(
      UUID orgId,
      UUID customerId,
      String orderNumber,
      OffsetDateTime placedAt,
      List<LineSpec> specs) {
    UUID orderId = UUID.randomUUID();
    SalesOrder order =
        SalesOrder.rehydrate(
            orderId,
            orgId,
            customerId,
            orderNumber,
            OrderChannel.ONLINE,
            "EGP",
            UUID.randomUUID().toString(),
            placedAt,
            OrderStatus.PENDING_PAYMENT,
            new BigDecimal("20.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("20.00"),
            BigDecimal.ZERO,
            placedAt,
            placedAt,
            placedAt.plusDays(1),
            null,
            null,
            null,
            null,
            null);
    List<SalesOrderLine> lines = new ArrayList<>();
    for (LineSpec s : specs) {
      lines.add(
          SalesOrderLine.rehydrate(
              UUID.randomUUID(),
              orderId,
              s.productId(),
              s.description(),
              s.qty(),
              new BigDecimal("10.00"),
              BigDecimal.ZERO,
              new BigDecimal("10.00").multiply(BigDecimal.valueOf(s.qty())),
              BigDecimal.ZERO,
              new BigDecimal("10.00").multiply(BigDecimal.valueOf(s.qty()))));
    }
    salesOrderRepositoryFactory.create(dsl).insert(order, lines);
  }
}
