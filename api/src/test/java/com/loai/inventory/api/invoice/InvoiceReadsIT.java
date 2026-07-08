package com.loai.inventory.api.invoice;

import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE_LINE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.FulfillmentStatus;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.InvoicePage;
import com.loai.inventory.service.InvoiceAdminService.InvoiceSummary;
import com.loai.inventory.service.InvoiceService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * Integration coverage for the invoice worklist read ({@code stories/invoice_reads.md}): {@code GET
 * /invoices} — queue-vs-ledger ordering, the {@code status} filter, page/size clamping, org
 * scoping, and the per-row batch-loaded {@code sales_order_number}.
 *
 * <p>Drives {@link InvoiceAdminService#list} directly against the real jOOQ repositories — no
 * Tomcat. VIEWER authorization and the {@code status}-param 400 are enforced in the handler ({@link
 * InvoiceHandlerAuthTest}), as everywhere. Invoices are seeded directly (the read never re-derives
 * invoice state, so the full delivery machinery would add nothing) with {@code created_at} pinned
 * so ordering assertions are deterministic.
 */
@Testcontainers
class InvoiceReadsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static InvoiceAdminService invoiceAdminService;

  private final AtomicInteger seq = new AtomicInteger(1);
  private final OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

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

    invoiceAdminService =
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
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchema() {
    dsl.execute(
        "TRUNCATE sales_invoice_line, sales_invoice, fulfillment, sales_order, org,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  @Test
  void issuedQueue_isOldestFirst_ledgerIsNewestFirst_voidOnlyInLedger() {
    UUID orgId = createOrg("acme");
    Order order = seedOrder(orgId, "500.00");
    // Three invoices for the order, created oldest → newest. i2 is VOID (a reissue predecessor).
    Invoice i1 = seedInvoice(orgId, order, "100.00", InvoiceStatus.ISSUED, base.plusMinutes(1));
    Invoice i2 = seedInvoice(orgId, order, "100.00", InvoiceStatus.VOID, base.plusMinutes(2));
    Invoice i3 = seedInvoice(orgId, order, "100.00", InvoiceStatus.ISSUED, base.plusMinutes(3));

    // Queue: only ISSUED, oldest first (collect the money first).
    InvoicePage queue = invoiceAdminService.list(orgId, InvoiceStatus.ISSUED, 0, 20);
    assertEquals(2, queue.total());
    assertEquals(List.of(i1.id(), i3.id()), ids(queue));

    // Ledger: every status incl. VOID, newest first.
    InvoicePage ledger = invoiceAdminService.list(orgId, null, 0, 20);
    assertEquals(3, ledger.total());
    assertEquals(List.of(i3.id(), i2.id(), i1.id()), ids(ledger));

    // The VOID filter isolates the cancelled document.
    assertEquals(List.of(i2.id()), ids(invoiceAdminService.list(orgId, InvoiceStatus.VOID, 0, 20)));
  }

  @Test
  void pageAndSize_areClampedNotErrors() {
    UUID orgId = createOrg("acme");
    Order order = seedOrder(orgId, "500.00");
    seedInvoice(orgId, order, "100.00", InvoiceStatus.ISSUED, base.plusMinutes(1));
    seedInvoice(orgId, order, "100.00", InvoiceStatus.ISSUED, base.plusMinutes(2));

    // page floors at 0, size clamps into [1, MAX]; total is the full filtered count.
    InvoicePage page = invoiceAdminService.list(orgId, null, -3, 0);
    assertEquals(1, page.items().size());
    assertEquals(2, page.total());
  }

  @Test
  void rows_carrySalesOrderNumberAndMoneyMeter() {
    UUID orgId = createOrg("acme");
    Order order = seedOrder(orgId, "500.00");
    Invoice inv = seedInvoice(orgId, order, "120.00", InvoiceStatus.ISSUED, base.plusMinutes(1));

    InvoiceSummary row = invoiceAdminService.list(orgId, null, 0, 20).items().get(0);

    assertEquals(inv.id(), row.invoice().getId());
    assertEquals(order.number(), row.salesOrderNumber());
    assertEquals(order.id(), row.invoice().getSalesOrderId());
    assertEquals("Nadia", row.invoice().getCustomerName());
    assertEquals(0, new BigDecimal("120.00").compareTo(row.invoice().getGrandTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(row.invoice().getPaidAmount()));
  }

  @Test
  void listing_isOrgScoped() {
    UUID orgId = createOrg("acme");
    Order order = seedOrder(orgId, "500.00");
    Invoice mine = seedInvoice(orgId, order, "100.00", InvoiceStatus.ISSUED, base.plusMinutes(1));

    UUID otherOrg = createOrg("other");
    Order otherOrder = seedOrder(otherOrg, "500.00");
    Invoice foreign =
        seedInvoice(otherOrg, otherOrder, "100.00", InvoiceStatus.ISSUED, base.plusMinutes(1));

    List<UUID> ours = ids(invoiceAdminService.list(orgId, null, 0, 20));
    assertEquals(List.of(mine.id()), ours);
    assertTrue(ours.stream().noneMatch(foreign.id()::equals));
    assertEquals(List.of(foreign.id()), ids(invoiceAdminService.list(otherOrg, null, 0, 20)));
  }

  // helpers

  private static List<UUID> ids(InvoicePage page) {
    return page.items().stream().map(v -> v.invoice().getId()).toList();
  }

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  private record Order(UUID id, String number) {}

  private Order seedOrder(UUID orgId, String grandTotal) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", seq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();
    return new Order(orderId, number);
  }

  private record Invoice(UUID id, String number) {}

  private Invoice seedInvoice(
      UUID orgId,
      Order order,
      String grandTotal,
      com.loai.inventory.repository.generated.enums.InvoiceStatus status,
      OffsetDateTime at) {
    UUID fulfillmentId = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillmentId)
        .set(FULFILLMENT.ORG_ID, orgId)
        .set(FULFILLMENT.SALES_ORDER_ID, order.id())
        .set(FULFILLMENT.STATUS, FulfillmentStatus.DELIVERED)
        .execute();

    UUID invoiceId = UUID.randomUUID();
    String number = "INV-2026-" + String.format("%04d", seq.getAndIncrement());
    dsl.insertInto(SALES_INVOICE)
        .set(SALES_INVOICE.ID, invoiceId)
        .set(SALES_INVOICE.ORG_ID, orgId)
        .set(SALES_INVOICE.SALES_ORDER_ID, order.id())
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
        .set(SALES_INVOICE_LINE.DESCRIPTION, "item")
        .set(SALES_INVOICE_LINE.QUANTITY, 1)
        .set(SALES_INVOICE_LINE.UNIT_PRICE, new BigDecimal(grandTotal))
        .set(SALES_INVOICE_LINE.LINE_SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE_LINE.LINE_TOTAL, new BigDecimal(grandTotal))
        .execute();
    return new Invoice(invoiceId, number);
  }

  private Invoice seedInvoice(
      UUID orgId, Order order, String grandTotal, InvoiceStatus status, OffsetDateTime at) {
    return seedInvoice(
        orgId,
        order,
        grandTotal,
        com.loai.inventory.repository.generated.enums.InvoiceStatus.valueOf(status.name()),
        at);
  }
}
