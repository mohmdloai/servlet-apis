package com.loai.inventory.api.invoice;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.InvoiceListFilter;
import com.loai.inventory.domain.model.InvoiceListFilter.PaidState;
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
import com.loai.inventory.service.InvoiceAdminService.InvoiceStatusCounts;
import com.loai.inventory.service.InvoiceService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * Integration coverage for the invoice worklist's filter dimensions ({@code
 * stories/invoice_filters.md}): the free-text {@code q} legs (invoice number, order number, the
 * frozen snapshot name folded both ways, the CRM name, phone digits), the half-open issued window,
 * the paid state, the amount bounds, the money summary the same predicate adds up to, and the
 * status counts. Drives {@link InvoiceAdminService} against the real jOOQ repositories, invoices
 * seeded directly (as {@link InvoiceReadsIT} does) with dates and meters pinned.
 */
@Testcontainers
class InvoiceFiltersIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static InvoiceAdminService service;

  private final AtomicInteger seq = new AtomicInteger(1);

  /** 1 Aug 2026 00:00 Cairo, as the frontend would send it: UTC, half-open. */
  private final OffsetDateTime aug1 = OffsetDateTime.parse("2026-07-31T21:00:00Z");

  private final OffsetDateTime sep1 = OffsetDateTime.parse("2026-08-31T21:00:00Z");

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

    service =
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
        "TRUNCATE sales_invoice_line, sales_invoice, fulfillment, sales_order, customer, org,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // q

  @Test
  void q_findsByInvoiceNumber_andByOrderNumber_caseInsensitive() {
    UUID org = createOrg("acme");
    Seeded a = seed(org, b().number("INV-2026-0112").orderNumber("SO-2026-00043"));
    seed(org, b().number("INV-2026-0115").orderNumber("SO-2026-00046"));

    assertEquals(List.of(a.id()), ids(list(org, q("0112"))));
    assertEquals(List.of(a.id()), ids(list(org, q("inv-2026-0112"))));
    assertEquals(List.of(a.id()), ids(list(org, q("00043"))));
    assertEquals(List.of(a.id()), ids(list(org, q("so-2026-00043"))));
    assertEquals(1, list(org, q("0112")).total(), "rows and total agree");
    assertEquals(2, list(org, q("inv-2026")).total());
  }

  @Test
  void q_foldsTheFrozenSnapshotName_andTheCrmName() {
    UUID org = createOrg("acme");
    UUID crm = createCustomer(org, "أحمد محمود", "+201001234567");
    Seeded viaCrm = seed(org, b().customerId(crm).customerName("A. Mahmoud"));
    Seeded snapshotOnly = seed(org, b().customerName("منى عادل"));
    seed(org, b().customerName("Hossam Ali"));

    // The CRM row's generated name_search: bare alef finds the hamza-seated alef.
    assertEquals(List.of(viaCrm.id()), ids(list(org, q("احمد"))));
    assertEquals(List.of(viaCrm.id()), ids(list(org, q("أحمد"))));
    // The frozen snapshot, folded in the query — no CRM row needed.
    assertEquals(List.of(snapshotOnly.id()), ids(list(org, q("منى"))));
    assertEquals(List.of(snapshotOnly.id()), ids(list(org, q("عادل"))));
    // Latin casefolds on the snapshot too.
    assertEquals(1, list(org, q("hossam")).total());
    assertEquals(1, list(org, q("MAHMOUD")).total(), "the snapshot leg, Latin");
  }

  @Test
  void q_matchesPhoneDigits_snapshotAndCrm() {
    UUID org = createOrg("acme");
    UUID crm = createCustomer(org, "Nadia", "+201001234567");
    Seeded viaCrm = seed(org, b().customerId(crm).customerName("Nadia"));
    Seeded snapshot = seed(org, b().customerName("Walk-in").customerPhone("011-2233-4455"));
    seed(org, b().customerName("Omar").customerPhone("+201555555555"));

    assertEquals(List.of(viaCrm.id()), ids(list(org, q("0100 123 4567"))));
    assertEquals(List.of(viaCrm.id()), ids(list(org, q("1001234567"))));
    assertEquals(List.of(snapshot.id()), ids(list(org, q("22334455"))));
    assertEquals(List.of(snapshot.id()), ids(list(org, q("011 2233"))));
    // Arabic-Indic digits fold before matching.
    assertEquals(List.of(snapshot.id()), ids(list(org, q("٢٢٣٣٤٤"))));
  }

  @Test
  void q_blankIsAbsent_andComposesWithStatus() {
    UUID org = createOrg("acme");
    Seeded issued = seed(org, b().customerName("Mona").status(InvoiceStatus.ISSUED));
    Seeded paid = seed(org, b().customerName("Mona").status(InvoiceStatus.PAID));

    assertEquals(2, list(org, q("   ")).total(), "whitespace-only q is no filter");
    InvoiceListFilter onQueue =
        new InvoiceListFilter(InvoiceStatus.ISSUED, "mona", null, null, null, null, null);
    assertEquals(List.of(issued.id()), ids(list(org, onQueue)));
    assertTrue(ids(list(org, q("mona"))).containsAll(List.of(issued.id(), paid.id())));
  }

  // issued window

  @Test
  void issuedWindow_isHalfOpen_onIssuedAt_eitherSideMayBeOpen() {
    UUID org = createOrg("acme");
    Seeded july = seed(org, b().issuedAt(aug1.minusSeconds(1)));
    Seeded aug1st = seed(org, b().issuedAt(aug1));
    Seeded aug31 = seed(org, b().issuedAt(sep1.minusSeconds(1)));
    Seeded sep1st = seed(org, b().issuedAt(sep1));

    List<UUID> august = ids(list(org, window(aug1, sep1)));
    assertEquals(2, august.size());
    assertTrue(august.containsAll(List.of(aug1st.id(), aug31.id())));
    assertTrue(!august.contains(july.id()) && !august.contains(sep1st.id()));

    assertEquals(3, list(org, window(aug1, null)).total(), "open end: 1 Aug onward");
    assertEquals(1, list(org, window(null, aug1)).total(), "open start: before 1 Aug");
    assertEquals(4, list(org, InvoiceListFilter.none()).total());
  }

  @Test
  void issuedWindow_narrowsButNeverReorders() {
    UUID org = createOrg("acme");
    Seeded older = seed(org, b().issuedAt(aug1.plusDays(1)).createdAt(aug1.plusDays(1)));
    Seeded newer = seed(org, b().issuedAt(aug1.plusDays(2)).createdAt(aug1.plusDays(2)));

    // Queue (ISSUED): oldest first. Ledger: newest first. The window changes neither.
    assertEquals(
        List.of(older.id(), newer.id()),
        ids(
            list(
                org,
                new InvoiceListFilter(InvoiceStatus.ISSUED, null, aug1, sep1, null, null, null))));
    assertEquals(List.of(newer.id(), older.id()), ids(list(org, window(aug1, sep1))));
  }

  // paid state

  @Test
  void paidState_none_isZeroMeter_partial_isStrictlyBetween() {
    UUID org = createOrg("acme");
    Seeded untouched = seed(org, b().total("640.00").paid("0"));
    Seeded partly = seed(org, b().total("2180.00").paid("1000.00"));
    Seeded settled = seed(org, b().total("320.00").paid("320.00").status(InvoiceStatus.PAID));

    assertEquals(List.of(untouched.id()), ids(list(org, paid(PaidState.NONE))));
    assertEquals(List.of(partly.id()), ids(list(org, paid(PaidState.PARTIAL))));
    assertTrue(!ids(list(org, paid(PaidState.PARTIAL))).contains(settled.id()));
  }

  // amount bounds

  @Test
  void amountBounds_areInclusive_onGrandTotal() {
    UUID org = createOrg("acme");
    Seeded small = seed(org, b().total("320.00"));
    Seeded mid = seed(org, b().total("500.00"));
    Seeded large = seed(org, b().total("3450.00"));

    List<UUID> atLeast500 = ids(list(org, amount("500", null)));
    assertEquals(2, atLeast500.size());
    assertTrue(atLeast500.containsAll(List.of(mid.id(), large.id())));
    assertEquals(List.of(small.id()), ids(list(org, amount(null, "499.99"))));
    assertEquals(List.of(mid.id()), ids(list(org, amount("500", "500"))));
  }

  // the money summary

  @Test
  void summary_addsUpTheFilteredSet_outstandingIsIssuedOnly_issuedExcludesVoid() {
    UUID org = createOrg("acme");
    seed(org, b().total("2180.00").paid("1000.00").status(InvoiceStatus.ISSUED));
    seed(org, b().total("1240.00").paid("500.00").status(InvoiceStatus.ISSUED));
    seed(org, b().total("760.00").paid("760.00").status(InvoiceStatus.PAID));
    seed(org, b().total("410.00").status(InvoiceStatus.VOID));

    InvoicePage ledger = list(org, InvoiceListFilter.none());
    assertEquals(4, ledger.total());
    assertEquals(0, new BigDecimal("1920.00").compareTo(ledger.stats().outstanding()));
    assertEquals(0, new BigDecimal("4180.00").compareTo(ledger.stats().issued()));

    // The summary follows the filter: partly-paid only.
    InvoicePage partly = list(org, paid(PaidState.PARTIAL));
    assertEquals(2, partly.total());
    assertEquals(0, new BigDecimal("1920.00").compareTo(partly.stats().outstanding()));
    assertEquals(0, new BigDecimal("3420.00").compareTo(partly.stats().issued()));

    // An empty set is 0.00, never null.
    InvoicePage none = list(org, q("nothing-matches"));
    assertEquals(0, none.total());
    assertEquals(0, BigDecimal.ZERO.compareTo(none.stats().outstanding()));
    assertEquals(0, BigDecimal.ZERO.compareTo(none.stats().issued()));
    assertEquals(2, none.stats().outstanding().scale());
  }

  // status counts

  @Test
  void statusCounts_everyStatusPresent_totalIsTheLedger_orgScoped() {
    UUID org = createOrg("acme");
    seed(org, b().status(InvoiceStatus.ISSUED));
    seed(org, b().status(InvoiceStatus.ISSUED));
    seed(org, b().status(InvoiceStatus.PAID));
    seed(org, b().status(InvoiceStatus.VOID));
    UUID other = createOrg("other");
    seed(other, b().status(InvoiceStatus.ISSUED));

    InvoiceStatusCounts counts = service.statusCounts(org);
    assertEquals(
        Map.of(
            InvoiceStatus.DRAFT, 0L,
            InvoiceStatus.ISSUED, 2L,
            InvoiceStatus.PAID, 1L,
            InvoiceStatus.VOID, 1L),
        counts.counts());
    assertEquals(4, counts.total());
    assertEquals(4, list(org, InvoiceListFilter.none()).total(), "== the All tab's total");
    assertEquals(1, service.statusCounts(other).total());
  }

  // helpers

  private static InvoicePage list(UUID org, InvoiceListFilter f) {
    return service.list(org, f, 0, 50);
  }

  private static List<UUID> ids(InvoicePage page) {
    return page.items().stream().map(v -> v.invoice().getId()).toList();
  }

  private static InvoiceListFilter q(String q) {
    return new InvoiceListFilter(null, q, null, null, null, null, null);
  }

  private static InvoiceListFilter window(OffsetDateTime from, OffsetDateTime to) {
    return new InvoiceListFilter(null, null, from, to, null, null, null);
  }

  private static InvoiceListFilter paid(PaidState state) {
    return new InvoiceListFilter(null, null, null, null, state, null, null);
  }

  private static InvoiceListFilter amount(String min, String max) {
    return new InvoiceListFilter(
        null,
        null,
        null,
        null,
        null,
        min == null ? null : new BigDecimal(min),
        max == null ? null : new BigDecimal(max));
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

  private UUID createCustomer(UUID orgId, String name, String phoneE164) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.EMAIL, "c-" + id + "@example.test")
        .set(CUSTOMER.NAME, name)
        .set(CUSTOMER.PHONE, phoneE164)
        .set(CUSTOMER.PHONE_E164, phoneE164)
        .execute();
    return id;
  }

  private record Seeded(UUID id, String number) {}

  /** One invoice's seed values — everything the filters read, each with a sensible default. */
  private static final class Spec {
    String number;
    String orderNumber;
    InvoiceStatus status = InvoiceStatus.ISSUED;
    String total = "100.00";
    String paid = "0";
    UUID customerId;
    String customerName = "Nadia";
    String customerPhone;
    OffsetDateTime issuedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    OffsetDateTime createdAt;

    Spec number(String v) {
      number = v;
      return this;
    }

    Spec orderNumber(String v) {
      orderNumber = v;
      return this;
    }

    Spec status(InvoiceStatus v) {
      status = v;
      return this;
    }

    Spec total(String v) {
      total = v;
      return this;
    }

    Spec paid(String v) {
      paid = v;
      return this;
    }

    Spec customerId(UUID v) {
      customerId = v;
      return this;
    }

    Spec customerName(String v) {
      customerName = v;
      return this;
    }

    Spec customerPhone(String v) {
      customerPhone = v;
      return this;
    }

    Spec issuedAt(OffsetDateTime v) {
      issuedAt = v;
      return this;
    }

    Spec createdAt(OffsetDateTime v) {
      createdAt = v;
      return this;
    }
  }

  private static Spec b() {
    return new Spec();
  }

  private Seeded seed(UUID orgId, Spec s) {
    int n = seq.getAndIncrement();
    UUID orderId = UUID.randomUUID();
    String orderNumber =
        s.orderNumber != null ? s.orderNumber : "SO-2026-" + String.format("%05d", n);
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.ORDER_NUMBER, orderNumber)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(s.total))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(s.total))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();

    UUID fulfillmentId = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillmentId)
        .set(FULFILLMENT.ORG_ID, orgId)
        .set(FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(FULFILLMENT.STATUS, FulfillmentStatus.DELIVERED)
        .execute();

    UUID invoiceId = UUID.randomUUID();
    String number = s.number != null ? s.number : "INV-2026-" + String.format("%04d", n);
    OffsetDateTime createdAt = s.createdAt != null ? s.createdAt : s.issuedAt;
    dsl.insertInto(SALES_INVOICE)
        .set(SALES_INVOICE.ID, invoiceId)
        .set(SALES_INVOICE.ORG_ID, orgId)
        .set(SALES_INVOICE.CUSTOMER_ID, s.customerId)
        .set(SALES_INVOICE.SALES_ORDER_ID, orderId)
        .set(SALES_INVOICE.FULFILLMENT_ID, fulfillmentId)
        .set(SALES_INVOICE.INVOICE_NUMBER, number)
        .set(
            SALES_INVOICE.STATUS,
            com.loai.inventory.repository.generated.enums.InvoiceStatus.valueOf(s.status.name()))
        .set(SALES_INVOICE.SUBTOTAL, new BigDecimal(s.total))
        .set(SALES_INVOICE.GRAND_TOTAL, new BigDecimal(s.total))
        .set(SALES_INVOICE.PAID_AMOUNT, new BigDecimal(s.paid))
        .set(SALES_INVOICE.CUSTOMER_NAME, s.customerName)
        .set(SALES_INVOICE.CUSTOMER_PHONE, s.customerPhone)
        .set(SALES_INVOICE.ISSUED_AT, s.issuedAt)
        .set(SALES_INVOICE.VOIDED_AT, s.status == InvoiceStatus.VOID ? s.issuedAt : null)
        .set(SALES_INVOICE.CREATED_AT, createdAt)
        .execute();
    return new Seeded(invoiceId, number);
  }
}
