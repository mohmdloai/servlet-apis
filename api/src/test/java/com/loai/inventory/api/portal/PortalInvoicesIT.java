package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE_LINE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PortalInvoiceResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.FulfillmentStatus;
import com.loai.inventory.repository.generated.enums.InvoiceStatus;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.CustomerPortalService.InvoiceDetail;
import com.loai.inventory.service.CustomerPortalService.InvoicePage;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Portal invoices / receipts (slice P3, {@code stories/portal_invoices.md}) end-to-end against real
 * Postgres, driving {@link CustomerPortalService#listInvoices} / {@link
 * CustomerPortalService#getInvoice} — the two customer-scoped reads behind {@code PortalServlet}'s
 * {@code GET /invoices[/{id}]} — plus the shared {@link DocumentRenderService} the {@code
 * /{id}/pdf} route streams once the ownership gate has passed. Covers the acceptance criteria:
 *
 * <ul>
 *   <li>AC1 — the list returns exactly the session customer's live invoices, newest first, paged;
 *       VOID predecessors and walk-in receipts (null customer) never appear; none → an empty page.
 *   <li>AC2 — a single invoice resolves for an owned id (with lines); a foreign, unknown, or voided
 *       id is the <em>same</em> opaque 404 (never an ownership oracle).
 *   <li>AC3 — the {@code /pdf} ownership gate is {@code getInvoice}, so a foreign id 404s before
 *       any render; an owned invoice renders non-empty {@code %PDF} bytes with a number-derived
 *       filename.
 *   <li>AC4 — the customer-safe body leaks no internal id (customer_id / sales_order_id /
 *       fulfillment_id / product_id / void_reason) on a JSON scan; cross-customer and cross-org
 *       isolation hold.
 * </ul>
 */
@Testcontainers
class PortalInvoicesIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CustomerPortalService portalService;
  static DocumentRenderService renderService;
  static final ObjectMapper mapper = ObjectMapperProvider.build();

  private final AtomicInteger seq = new AtomicInteger(1);

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

    portalService =
        new CustomerPortalService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new CustomerAddressRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl());

    // The real renderer, exactly as the servlet's PDF route uses it: renderInvoice() reads only the
    // org profile + the invoice aggregate, so the credit-note / payment collaborators are unused
    // here (null). Text-only header (no logo source) — a broken logo must never break a download.
    OrgService orgService =
        new OrgService(dsl, new OrgRepositoryFactoryImpl(), new UserRepositoryFactoryImpl(), null);
    InvoiceAdminService invoiceAdminService =
        new InvoiceAdminService(
            dsl,
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            new InvoiceService(
                new SalesInvoiceRepositoryFactoryImpl(),
                new PaymentRepositoryFactoryImpl(),
                new PaymentAllocationRepositoryFactoryImpl()));
    renderService = new DocumentRenderService(orgService, invoiceAdminService, null, null);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE sales_invoice_line, sales_invoice, fulfillment, sales_order_line, sales_order,"
            + " product, customer, org, invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // AC1: list is own-only, live-only, newest-first, paged

  @Test
  void listReturnsOnlyOwnLiveInvoices_newestFirst_paged() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");
    UUID orderA = seedOrder(org, custA);
    UUID orderB = seedOrder(org, custB);

    OffsetDateTime t0 = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    // Three of A's live invoices oldest → newest, plus one VOID (a reissue predecessor) that must
    // never surface, one of B's, and one walk-in (null customer) receipt.
    Seeded a1 = seedInvoice(org, orderA, custA, "100.00", InvoiceStatus.ISSUED, t0);
    seedInvoice(org, orderA, custA, "100.00", InvoiceStatus.VOID, t0.plusMinutes(1));
    Seeded a2 = seedInvoice(org, orderA, custA, "100.00", InvoiceStatus.ISSUED, t0.plusMinutes(2));
    Seeded a3 = seedInvoice(org, orderA, custA, "100.00", InvoiceStatus.PAID, t0.plusMinutes(3));
    seedInvoice(org, orderB, custB, "100.00", InvoiceStatus.ISSUED, t0.plusMinutes(1));
    seedInvoice(org, orderA, null, "100.00", InvoiceStatus.ISSUED, t0.plusMinutes(4));

    InvoicePage page0 = portalService.listInvoices(org, custA, 0, 2);
    assertEquals(3, page0.total(), "counts only A's live invoices (VOID + walk-in excluded)");
    assertEquals(
        List.of(a3.id(), a2.id()),
        page0.items().stream().map(i -> i.getId()).toList(),
        "page 0 is the two newest, created_at DESC");

    InvoicePage page1 = portalService.listInvoices(org, custA, 1, 2);
    assertEquals(
        List.of(a1.id()),
        page1.items().stream().map(i -> i.getId()).toList(),
        "page 1 is the oldest live invoice; the VOID and B's invoice never leak in");
  }

  @Test
  void customerWithNoInvoices_emptyPage() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "quiet@acme.test");

    InvoicePage page = portalService.listInvoices(org, cust, 0, 20);
    assertEquals(0, page.total());
    assertTrue(page.items().isEmpty());
  }

  // AC2 / AC4: single-invoice read is ownership-scoped, opaque 404

  @Test
  void getInvoice_ownedResolves_foreignUnknownOrVoidIsTheSame404() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");
    UUID orderA = seedOrder(org, custA);
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    Seeded owned = seedInvoice(org, orderA, custA, "120.00", InvoiceStatus.ISSUED, now);
    Seeded voided = seedInvoice(org, orderA, custA, "120.00", InvoiceStatus.VOID, now);

    InvoiceDetail detail = portalService.getInvoice(org, custA, owned.id());
    assertEquals(owned.id(), detail.invoice().getId());
    assertFalse(detail.lines().isEmpty(), "lines come back on the detail read");

    // A foreign customer, an unknown id, and a voided (but owned) id are indistinguishable — 404.
    assertThrows(
        NotFoundException.class,
        () -> portalService.getInvoice(org, custB, owned.id()),
        "customer B cannot read customer A's invoice");
    assertThrows(
        NotFoundException.class,
        () -> portalService.getInvoice(org, custA, UUID.randomUUID()),
        "an unknown id is the same 404");
    assertThrows(
        NotFoundException.class,
        () -> portalService.getInvoice(org, custA, voided.id()),
        "a voided invoice is not exposed to the customer");
  }

  @Test
  void reads_areOrgScoped_sameCustomerIdInAnotherOrgSeesNothing() {
    UUID orgA = createOrg();
    UUID orgB = createOrg();
    UUID cust = createCustomer(orgA, "a@acme.test");
    UUID orderA = seedOrder(orgA, cust);
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    Seeded inv = seedInvoice(orgA, orderA, cust, "100.00", InvoiceStatus.ISSUED, now);

    assertEquals(0, portalService.listInvoices(orgB, cust, 0, 20).total());
    assertThrows(NotFoundException.class, () -> portalService.getInvoice(orgB, cust, inv.id()));
  }

  @Test
  void customerSafeBody_carriesTheHandle_leaksNoInternalFields() throws Exception {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    UUID order = seedOrder(org, cust);
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    Seeded inv = seedInvoice(org, order, cust, "120.00", InvoiceStatus.ISSUED, now);

    InvoiceDetail detail = portalService.getInvoice(org, cust, inv.id());
    String json =
        mapper.writeValueAsString(PortalInvoiceResponse.from(detail.invoice(), detail.lines()));

    assertTrue(json.contains("\"invoice_number\""), "the number the customer knows is present");
    assertTrue(
        json.contains("\"id\""), "the invoice id is the addressing handle — intentionally kept");
    for (String forbidden :
        List.of("customer_id", "sales_order_id", "fulfillment_id", "product_id", "void_reason")) {
      assertFalse(json.contains(forbidden), "must not leak " + forbidden + " — got " + json);
    }
  }

  // AC3: the PDF route renders for an owned invoice; the gate is getInvoice

  @Test
  void pdf_rendersForOwnedInvoice_withNumberDerivedFilename() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    UUID order = seedOrder(org, cust);
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    Seeded inv = seedInvoice(org, order, cust, "150.00", InvoiceStatus.ISSUED, now);

    // The servlet asserts ownership via getInvoice, then streams renderInvoice's bytes.
    portalService.getInvoice(org, cust, inv.id());
    RenderedDocument doc = renderService.renderInvoice(org, inv.id());

    assertTrue(doc.bytes().length > 0, "PDF is non-empty");
    String head = new String(doc.bytes(), 0, 5, StandardCharsets.US_ASCII);
    assertEquals("%PDF-", head, "starts with the PDF magic bytes");
    assertEquals(inv.number() + ".pdf", doc.filename(), "filename derives from the invoice number");
  }

  @Test
  void pdf_foreignInvoice_gateThrowsBeforeAnyRender() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");
    UUID orderA = seedOrder(org, custA);
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    Seeded inv = seedInvoice(org, orderA, custA, "150.00", InvoiceStatus.ISSUED, now);

    // B hitting /{A's invoice}/pdf 404s at the gate — render is never reached.
    assertThrows(NotFoundException.class, () -> portalService.getInvoice(org, custB, inv.id()));
  }

  // helpers

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

  private UUID seedOrder(UUID orgId, UUID customerId) {
    UUID orderId = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.CUSTOMER_ID, customerId)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-2026-" + String.format("%05d", seq.getAndIncrement()))
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal("100.00"))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("100.00"))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();
    return orderId;
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

  private record Seeded(UUID id, String number) {}

  /** Seed one issued/void/paid invoice + a line, mirroring InvoiceReadsIT's direct-insert style. */
  private Seeded seedInvoice(
      UUID orgId,
      UUID orderId,
      UUID customerId,
      String grandTotal,
      InvoiceStatus status,
      OffsetDateTime at) {
    UUID fulfillmentId = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillmentId)
        .set(FULFILLMENT.ORG_ID, orgId)
        .set(FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(FULFILLMENT.STATUS, FulfillmentStatus.DELIVERED)
        .execute();

    UUID invoiceId = UUID.randomUUID();
    String number = "INV-2026-" + String.format("%04d", seq.getAndIncrement());
    dsl.insertInto(SALES_INVOICE)
        .set(SALES_INVOICE.ID, invoiceId)
        .set(SALES_INVOICE.ORG_ID, orgId)
        .set(SALES_INVOICE.CUSTOMER_ID, customerId)
        .set(SALES_INVOICE.SALES_ORDER_ID, orderId)
        .set(SALES_INVOICE.FULFILLMENT_ID, fulfillmentId)
        .set(SALES_INVOICE.INVOICE_NUMBER, number)
        .set(SALES_INVOICE.STATUS, status)
        .set(SALES_INVOICE.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE.CUSTOMER_NAME, "Nadia")
        .set(SALES_INVOICE.ISSUED_AT, at)
        .set(SALES_INVOICE.CREATED_AT, at)
        .execute();

    dsl.insertInto(SALES_INVOICE_LINE)
        .set(SALES_INVOICE_LINE.ID, UUID.randomUUID())
        .set(SALES_INVOICE_LINE.SALES_INVOICE_ID, invoiceId)
        .set(SALES_INVOICE_LINE.PRODUCT_ID, createProduct(orgId))
        .set(SALES_INVOICE_LINE.DESCRIPTION, "Widget")
        .set(SALES_INVOICE_LINE.QUANTITY, 1)
        .set(SALES_INVOICE_LINE.UNIT_PRICE, new BigDecimal(grandTotal))
        .set(SALES_INVOICE_LINE.LINE_SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE_LINE.LINE_TOTAL, new BigDecimal(grandTotal))
        .execute();
    return new Seeded(invoiceId, number);
  }
}
