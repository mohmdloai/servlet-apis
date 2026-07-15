package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicOrderResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.CustomerPortalService.OrderPage;
import com.loai.inventory.service.CustomerPortalService.OrderView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Portal order history (slice P2, {@code stories/portal_order_reads.md}) end-to-end against real
 * Postgres, driving {@link CustomerPortalService#listOrders} / {@link
 * CustomerPortalService#getOrder} — the two customer-scoped reads the {@code PortalServlet}
 * exposes. Covers the acceptance criteria:
 *
 * <ul>
 *   <li>AC1 — the list returns exactly the session customer's orders, newest first, paged; a
 *       customer with none → an empty page.
 *   <li>AC2 — a single order resolves for an owned number; a foreign or unknown number is the
 *       <em>same</em> opaque 404 (never an ownership oracle).
 *   <li>AC3 — the customer-safe body leaks no internal id / product_id / customer_id / org_id /
 *       prepaid / channel (JSON scan), identical to the public tracker's shape.
 *   <li>AC4 — cross-customer isolation: customer B can never read customer A's order or list.
 * </ul>
 */
@Testcontainers
class PortalOrdersIT {

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
            dsl, new CustomerRepositoryFactoryImpl(), salesOrderRepositoryFactory);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE sales_order_line, sales_order, product, customer, org RESTART IDENTITY CASCADE");
  }

  // ── AC1: list is own-only, newest-first, paged ───────────────────────────────

  @Test
  void listReturnsOnlyOwnOrders_newestFirst_paged() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");

    OffsetDateTime t0 = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    // Three of A's orders, placed oldest → newest, plus one of B's (must never appear).
    seedOrder(org, custA, "SO-1001", t0, false);
    seedOrder(org, custA, "SO-1002", t0.plusDays(1), false);
    seedOrder(org, custA, "SO-1003", t0.plusDays(2), false);
    seedOrder(org, custB, "SO-2001", t0.plusHours(1), false);

    OrderPage page0 = portalService.listOrders(org, custA, 0, 2);
    assertEquals(3, page0.total(), "total counts only A's orders");
    assertEquals(
        List.of("SO-1003", "SO-1002"),
        page0.items().stream().map(v -> v.order().getOrderNumber()).toList(),
        "page 0 is the two newest, placed_at DESC");

    OrderPage page1 = portalService.listOrders(org, custA, 1, 2);
    assertEquals(
        List.of("SO-1001"),
        page1.items().stream().map(v -> v.order().getOrderNumber()).toList(),
        "page 1 is the oldest, and B's order never leaks in");
  }

  @Test
  void customerWithNoOrders_emptyPage() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "quiet@acme.test");

    OrderPage page = portalService.listOrders(org, cust, 0, 20);
    assertEquals(0, page.total());
    assertTrue(page.items().isEmpty());
  }

  // ── AC2 / AC4: single-order read is ownership-scoped, opaque 404 ──────────────

  @Test
  void getOrder_ownedResolves_foreignOrUnknownIsTheSame404() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    seedOrder(org, custA, "SO-1001", now, true);

    // Owner reads it.
    OrderView owned = portalService.getOrder(org, custA, "SO-1001");
    assertEquals("SO-1001", owned.order().getOrderNumber());
    assertFalse(owned.lines().isEmpty(), "lines come back on the detail read");

    // A foreign customer and an unknown number are indistinguishable — both 404.
    assertThrows(
        NotFoundException.class,
        () -> portalService.getOrder(org, custB, "SO-1001"),
        "customer B cannot read customer A's order");
    assertThrows(
        NotFoundException.class,
        () -> portalService.getOrder(org, custA, "SO-9999"),
        "an unknown number is the same 404");
  }

  @Test
  void listIsOrgScoped_sameCustomerIdInAnotherOrgSeesNothing() {
    UUID orgA = createOrg();
    UUID orgB = createOrg();
    UUID cust = createCustomer(orgA, "a@acme.test");
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    seedOrder(orgA, cust, "SO-1001", now, false);

    // The same customerId, queried under a foreign org, resolves nothing.
    assertEquals(0, portalService.listOrders(orgB, cust, 0, 20).total());
    assertThrows(NotFoundException.class, () -> portalService.getOrder(orgB, cust, "SO-1001"));
  }

  // ── AC3: no internal fields leak, shape identical to the public tracker ───────

  @Test
  void customerSafeBody_leaksNoInternalFields() throws Exception {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    seedOrder(org, cust, "SO-1001", now, true);

    OrderView view = portalService.getOrder(org, cust, "SO-1001");
    String json =
        mapper.writeValueAsString(PublicOrderResponse.forOrderView(view.order(), view.lines()));

    assertTrue(json.contains("\"order_number\""), "the number the customer knows is present");
    for (String forbidden :
        List.of(
            "\"id\"", "product_id", "customer_id", "org_id", "prepaid", "channel", "idempotency")) {
      assertFalse(json.contains(forbidden), "must not leak " + forbidden + " — got " + json);
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

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

  /** Seed a placed order (optionally with one line) via the real repository insert. */
  private void seedOrder(
      UUID orgId, UUID customerId, String orderNumber, OffsetDateTime placedAt, boolean withLine) {
    UUID orderId = UUID.randomUUID();
    SalesOrder order =
        SalesOrder.rehydrate(
            orderId,
            orgId,
            customerId,
            orderNumber,
            OrderChannel.ONLINE,
            "EGP",
            /* idempotencyKey= */ UUID.randomUUID().toString(),
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
    List<SalesOrderLine> lines = List.of();
    if (withLine) {
      UUID productId = createProduct(orgId);
      lines =
          List.of(
              SalesOrderLine.rehydrate(
                  UUID.randomUUID(),
                  orderId,
                  productId,
                  "Widget",
                  2,
                  new BigDecimal("10.00"),
                  BigDecimal.ZERO,
                  new BigDecimal("20.00"),
                  BigDecimal.ZERO,
                  new BigDecimal("20.00")));
    }
    salesOrderRepositoryFactory.create(dsl).insert(order, lines);
  }
}
