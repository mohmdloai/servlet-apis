package com.loai.inventory.api.reports;

import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.report.AgingBand;
import com.loai.inventory.domain.model.report.InventoryValuation;
import com.loai.inventory.domain.model.report.ProfitTotals;
import com.loai.inventory.domain.model.report.RevenuePoint;
import com.loai.inventory.domain.model.report.SalesPoint;
import com.loai.inventory.domain.model.report.TopProduct;
import com.loai.inventory.repository.ReportRepositoryImpl;
import com.loai.inventory.repository.generated.enums.FulfillmentStatus;
import com.loai.inventory.repository.generated.enums.InvoiceStatus;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentProvider;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.RefundStatus;
import com.loai.inventory.service.ReportService;
import com.loai.inventory.service.ReportService.ArAgingReport;
import com.loai.inventory.service.ReportService.InventoryValuationReport;
import com.loai.inventory.service.ReportService.RevenueReport;
import com.loai.inventory.service.ReportService.SalesReport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Integration coverage for the dashboard aggregates ({@code stories/reporting_reads.md}) against a
 * real Postgres: each of the five reports asserts its sums/rankings/bands against seeded rows, plus
 * org-isolation. Exercises {@link ReportService} over {@link ReportRepositoryImpl} — the same
 * wiring {@code AppConfig} builds.
 */
@Testcontainers
class ReportReadsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ReportService reports;

  private final AtomicInteger seq = new AtomicInteger(1);

  // Three consecutive UTC days used across the windowed reports.
  private static final OffsetDateTime D1 =
      OffsetDateTime.of(2026, 3, 1, 6, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime D2 =
      OffsetDateTime.of(2026, 3, 2, 6, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime D3 =
      OffsetDateTime.of(2026, 3, 3, 6, 0, 0, 0, ZoneOffset.UTC);
  private static final String FROM = "2026-03-01T00:00:00Z";
  private static final String TO = "2026-03-04T00:00:00Z";

  private static OffsetDateTime day(int d) {
    return OffsetDateTime.of(2026, 3, d, 0, 0, 0, 0, ZoneOffset.UTC);
  }

  @BeforeAll
  static void startInfra() {
    org.flywaydb.core.Flyway.configure()
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
    reports = new ReportService(new ReportRepositoryImpl(dsl));
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
        "TRUNCATE refund, payment, payment_transaction, sales_invoice, fulfillment,"
            + " sales_order_line, sales_order, inventory, product, org RESTART IDENTITY CASCADE");
  }

  // G1 · revenue

  @Test
  void revenue_sumsInvoicedCollectedRefunded_excludingVoidDraftPending() {
    UUID org = createOrg("acme");
    // invoiced (ISSUED/PAID by issued_at); VOID and DRAFT excluded.
    invoice(org, InvoiceStatus.ISSUED, "100.00", "0.00", D1);
    invoice(org, InvoiceStatus.PAID, "200.00", "200.00", D2);
    invoice(org, InvoiceStatus.VOID, "999.00", "0.00", D1);
    invoice(org, InvoiceStatus.DRAFT, "50.00", "0.00", null);
    // collected (all payments by received_at).
    payment(org, "80.00", D1);
    payment(org, "150.00", D2);
    payment(org, "30.00", D3);
    // refunded (EXECUTED only).
    refund(org, RefundStatus.EXECUTED, "20.00", D2);
    refund(org, RefundStatus.PENDING, "999.00", null);
    refund(org, RefundStatus.CANCELLED, "999.00", null);

    RevenueReport r = reports.revenue(org, FROM, TO, "day");
    List<RevenuePoint> s = r.series();
    assertEquals(3, s.size(), "one bucket per active day");
    assertPoint(s.get(0), day(1), "100.00", "80.00", "0.00");
    assertPoint(s.get(1), day(2), "200.00", "150.00", "20.00");
    assertPoint(s.get(2), day(3), "0.00", "30.00", "0.00");
    assertEquals(0, new BigDecimal("300.00").compareTo(r.totalInvoiced()));
    assertEquals(0, new BigDecimal("260.00").compareTo(r.totalCollected()));
    assertEquals(0, new BigDecimal("20.00").compareTo(r.totalRefunded()));
  }

  @Test
  void revenue_emptyBucketsAreOmitted() {
    UUID org = createOrg("acme");
    payment(org, "10.00", D1);
    payment(org, "10.00", D3); // D2 has nothing → must be absent from the sparse series.

    List<RevenuePoint> s = reports.revenue(org, FROM, TO, "day").series();
    assertEquals(2, s.size());
    assertEquals(day(1), s.get(0).period());
    assertEquals(day(3), s.get(1).period());
  }

  // G2 · sales by channel

  @Test
  void sales_groupsByChannel_onlyMoneyCommittedStatuses() {
    UUID org = createOrg("acme");
    order(org, OrderChannel.ONLINE, OrderStatus.PAID, "100.00", D1);
    order(org, OrderChannel.ONLINE, OrderStatus.PENDING_PAYMENT, "999.00", D1); // excluded
    order(org, OrderChannel.IN_STORE, OrderStatus.CLOSED, "50.00", D1);
    order(org, OrderChannel.ONLINE, OrderStatus.FULFILLING, "70.00", D2);
    order(org, OrderChannel.PHONE, OrderStatus.CANCELLED, "999.00", D2); // excluded

    SalesReport r = reports.sales(org, FROM, TO, "day", null);
    // day1: IN_STORE(1,50), ONLINE(1,100); day2: ONLINE(1,70) — ordered period ASC, channel ASC.
    List<SalesPoint> s = r.series();
    assertEquals(3, s.size());
    assertSales(s.get(0), day(1), "IN_STORE", 1, "50.00");
    assertSales(s.get(1), day(1), "ONLINE", 1, "100.00");
    assertSales(s.get(2), day(2), "ONLINE", 1, "70.00");
    assertEquals(3, r.totalOrders());
    assertEquals(0, new BigDecimal("220.00").compareTo(r.totalGross()));

    // channel filter narrows to IN_STORE only.
    SalesReport inStore = reports.sales(org, FROM, TO, "day", "IN_STORE");
    assertEquals(1, inStore.series().size());
    assertEquals("IN_STORE", inStore.series().get(0).channel());
    assertEquals(1, inStore.totalOrders());
  }

  @Test
  void sales_windowIsHalfOpen_orderAtToIsExcluded() {
    UUID org = createOrg("acme");
    // Exactly at `to` (2026-03-04T00:00Z) → excluded; just before → included.
    order(org, OrderChannel.ONLINE, OrderStatus.PAID, "10.00", day(4));
    order(org, OrderChannel.ONLINE, OrderStatus.PAID, "10.00", day(4).minusSeconds(1));

    SalesReport r = reports.sales(org, FROM, TO, "day", null);
    assertEquals(1, r.totalOrders(), "the order stamped exactly at `to` is excluded");
  }

  // G3 · top products

  @Test
  void topProducts_rankByRevenueAndQuantity_excludingNonSaleLines() {
    UUID org = createOrg("acme");
    UUID p1 = product(org, "Widget", "5.00");
    UUID p2 = product(org, "Gadget", "5.00");
    UUID p3 = product(org, "Gizmo", "5.00");
    UUID paid = order(org, OrderChannel.ONLINE, OrderStatus.PAID, "0.00", D1);
    line(paid, p1, 5, "500.00");
    line(paid, p2, 10, "200.00");
    line(paid, p3, 1, "50.00");
    // A CANCELLED order's lines must never contribute.
    UUID cancelled = order(org, OrderChannel.ONLINE, OrderStatus.CANCELLED, "0.00", D1);
    line(cancelled, p3, 100, "9999.00");

    List<TopProduct> byRev = reports.topProducts(org, FROM, TO, "revenue", null).items();
    assertEquals(List.of(p1, p2, p3), byRev.stream().map(TopProduct::productId).toList());
    assertEquals(0, new BigDecimal("500.00").compareTo(byRev.get(0).revenue()));
    assertEquals(5, byRev.get(0).quantity());

    List<TopProduct> byQty = reports.topProducts(org, FROM, TO, "quantity", null).items();
    assertEquals(List.of(p2, p1, p3), byQty.stream().map(TopProduct::productId).toList());
    assertEquals(10, byQty.get(0).quantity());

    // limit caps the ranked output.
    assertEquals(2, reports.topProducts(org, FROM, TO, "revenue", "2").items().size());
  }

  // G4 · AR aging

  @Test
  void arAging_bucketsByAge_excludesFullyPaidAndVoid() {
    UUID org = createOrg("acme");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    invoice(org, InvoiceStatus.ISSUED, "100.00", "0.00", now.minusDays(10)); // band 0-30 → 100
    invoice(org, InvoiceStatus.ISSUED, "200.00", "50.00", now.minusDays(45)); // band 31-60 → 150
    invoice(org, InvoiceStatus.ISSUED, "300.00", "0.00", now.minusDays(100)); // band 90+ → 300
    invoice(org, InvoiceStatus.ISSUED, "100.00", "100.00", now.minusDays(5)); // fully paid → out
    invoice(org, InvoiceStatus.VOID, "999.00", "0.00", now.minusDays(20)); // void → out

    ArAgingReport r = reports.arAging(org, null);
    List<AgingBand> b = r.bands();
    assertEquals(4, b.size());
    assertBand(b.get(0), "0-30", 1, "100.00");
    assertBand(b.get(1), "31-60", 1, "150.00");
    assertBand(b.get(2), "61-90", 0, "0.00"); // always present, zero-filled
    assertBand(b.get(3), "90+", 1, "300.00");
    assertEquals(3, r.totalCount());
    assertEquals(0, new BigDecimal("550.00").compareTo(r.totalOutstanding()));

    // custom edges re-band the same population.
    ArAgingReport custom = reports.arAging(org, "15,45");
    assertEquals(3, custom.bands().size());
    assertBand(custom.bands().get(0), "0-15", 1, "100.00"); // age 10
    assertBand(custom.bands().get(1), "16-45", 1, "150.00"); // age 45
    assertBand(custom.bands().get(2), "45+", 1, "300.00"); // age 100
  }

  // G5 · inventory valuation

  @Test
  void inventoryValuation_retailValueOverTrackedProductsOnly() {
    UUID org = createOrg("acme");
    UUID p1 = product(org, "A", "100.00");
    UUID p2 = product(org, "B", "50.00");
    UUID p3 = product(org, "C", "20.00");
    product(org, "Untracked", "999.00"); // no inventory row → excluded
    inventory(org, p1, 10); // 1000
    inventory(org, p2, 0); // 0, out of stock
    inventory(org, p3, 5); // 100

    InventoryValuationReport r = reports.inventoryValuation(org);
    assertEquals(3, r.valuation().trackedProducts());
    assertEquals(15, r.valuation().totalUnits());
    assertEquals(0, new BigDecimal("1100.00").compareTo(r.valuation().retailValue()));
    assertEquals(1, r.valuation().outOfStock());
  }

  // G5 · cost value (stories/product_cost_and_margin.md)

  @Test
  void valuation_costValueOverCostedOnly_andCoverage() {
    UUID org = createOrg("acme");
    UUID a = product(org, "A", "100.00", "60.00"); // 10 × 60 = 600
    UUID b = product(org, "B", "50.00", "20.00"); // 2 × 20 = 40
    UUID c = product(org, "C", "20.00", null); // uncosted, 5 units
    inventory(org, a, 10);
    inventory(org, b, 2);
    inventory(org, c, 5);

    InventoryValuation v = reports.inventoryValuation(org).valuation();
    assertEquals(3, v.trackedProducts());
    assertEquals(17, v.totalUnits());
    assertMoney("1200.00", v.retailValue(), "retail over every tracked product"); // 1000+100+100
    assertMoney("640.00", v.costValue(), "cost over the costed products only");
    assertEquals(1, v.uncostedProducts());
    assertEquals(5, v.uncostedUnits());
  }

  @Test
  void valuation_noCostedProduct_costValueIsNull_neverZero() {
    UUID org = createOrg("acme");
    UUID a = product(org, "A", "100.00", null);
    UUID b = product(org, "B", "50.00", null);
    inventory(org, a, 3);
    inventory(org, b, 4);

    InventoryValuation v = reports.inventoryValuation(org).valuation();
    assertNull(v.costValue(), "no costed product → null (the key stays off the wire)");
    assertEquals(2, v.uncostedProducts());
    assertEquals(7, v.uncostedUnits());
    // A costed product with zero stock is a real 0.00, not "unknown".
    UUID c = product(org, "C", "10.00", "4.00");
    inventory(org, c, 0);
    assertMoney("0.00", reports.inventoryValuation(org).valuation().costValue(), "costed, empty");
  }

  // G3/G6 · profit (stories/product_cost_and_margin.md)

  @Test
  void topProducts_costFieldsOverCostedLinesOnly() {
    UUID org = createOrg("acme");
    UUID nb = product(org, "Notebook", "50.00", "30.00");
    UUID pen = product(org, "Pen", "5.00", null);
    // Sold 5 BEFORE costing (snapshot null) and 3 AFTER (snapshot 30.00), no tax, no discount.
    UUID before = order(org, OrderStatus.CLOSED, "250.00", "0.00", "250.00", D1);
    costedLine(before, nb, 5, "50.00", "0", null);
    UUID after = order(org, OrderStatus.CLOSED, "160.00", "0.00", "160.00", D2);
    costedLine(after, nb, 3, "50.00", "0", "30.00");
    costedLine(after, pen, 2, "5.00", "0", null);

    List<TopProduct> rows = reports.topProducts(org, FROM, TO, "revenue", null).items();
    TopProduct notebook = rows.stream().filter(r -> r.productId().equals(nb)).findFirst().get();
    assertEquals(8, notebook.quantity(), "every unit sold");
    assertMoney("400.00", notebook.revenue(), "revenue is untouched");
    assertEquals(3, notebook.costedQuantity(), "only the units sold under a cost snapshot");
    assertMoney("150.00", notebook.costedNetSales(), "net sales on the costed lines");
    assertMoney("90.00", notebook.cost(), "3 × 30");
    assertMoney("60.00", notebook.grossProfit(), "150 − 90");

    TopProduct p = rows.stream().filter(r -> r.productId().equals(pen)).findFirst().get();
    assertEquals(2, p.quantity());
    assertEquals(0, p.costedQuantity());
    assertNull(p.costedNetSales());
    assertNull(p.cost());
    assertNull(p.grossProfit());
  }

  @Test
  void topProducts_netSalesExcludesTaxAndProratesTheOrderDiscount() {
    UUID org = createOrg("acme");
    UUID a = product(org, "A", "100.00", "60.00");
    UUID b = product(org, "B", "100.00", "40.00");
    // 14 % tax; goods subtotal 300, a 10 % counter discount = 30 on the ORDER.
    UUID sale = order(org, OrderStatus.CLOSED, "300.00", "30.00", "307.80", D1);
    costedLine(sale, a, 2, "100.00", "0.14", "60.00"); // subtotal 200, tax 28, total 228
    costedLine(sale, b, 1, "100.00", "0.14", "40.00"); // subtotal 100, tax 14, total 114

    List<TopProduct> rows = reports.topProducts(org, FROM, TO, "revenue", null).items();
    TopProduct ra = rows.stream().filter(r -> r.productId().equals(a)).findFirst().get();
    TopProduct rb = rows.stream().filter(r -> r.productId().equals(b)).findFirst().get();
    // revenue stays tax-inclusive and pre-discount: the shipped field.
    assertMoney("228.00", ra.revenue(), "A revenue");
    assertMoney("114.00", rb.revenue(), "B revenue");
    // net sales: ex-tax, minus the discount prorated by subtotal share (200/300 → 20, 100/300 →
    // 10).
    assertMoney("180.00", ra.costedNetSales(), "A net sales");
    assertMoney("90.00", rb.costedNetSales(), "B net sales");
    assertMoney("120.00", ra.cost(), "A cost");
    assertMoney("60.00", ra.grossProfit(), "A profit");
    assertMoney("40.00", rb.cost(), "B cost");
    assertMoney("50.00", rb.grossProfit(), "B profit");

    // And the window total is the same arithmetic one grain up.
    ProfitTotals t = reports.profit(org, FROM, TO).totals();
    assertEquals(3, t.quantity());
    assertEquals(3, t.costedQuantity());
    assertMoney("270.00", t.costedNetSales(), "Σ net sales");
    assertMoney("160.00", t.cost(), "Σ cost");
    assertMoney("110.00", t.grossProfit(), "Σ profit");
  }

  @Test
  void topProducts_byProfit_ordersDescWithUncostedLast_andIs400OnUnknownBy() {
    UUID org = createOrg("acme");
    UUID low = product(org, "Low", "10.00", "9.00"); // profit 1 × 10 = 10
    UUID high = product(org, "High", "10.00", "2.00"); // profit 8 × 5 = 40
    UUID none = product(org, "None", "10.00", null); // uncosted → last
    UUID sale = order(org, OrderStatus.PAID, "160.00", "0.00", "160.00", D1);
    costedLine(sale, low, 10, "10.00", "0", "9.00");
    costedLine(sale, high, 5, "10.00", "0", "2.00");
    costedLine(sale, none, 1, "10.00", "0", null);

    var report = reports.topProducts(org, FROM, TO, "profit", null);
    assertEquals("profit", report.by());
    assertEquals(
        List.of(high, low, none), report.items().stream().map(TopProduct::productId).toList());
    // Revenue order is different (Low's 100 beats High's 50), so the sort really keyed on profit.
    assertEquals(
        List.of(low, high, none),
        reports.topProducts(org, FROM, TO, "revenue", null).items().stream()
            .map(TopProduct::productId)
            .toList());
    assertThrows(
        ValidationException.class, () -> reports.topProducts(org, FROM, TO, "margin", null));
  }

  @Test
  void profit_totalEqualsSumOfRows_excludesNonSales_emptyWindowIsZerosAndNulls() {
    UUID org = createOrg("acme");
    UUID a = product(org, "A", "20.00", "12.00");
    UUID b = product(org, "B", "30.00", "10.00");
    UUID c = product(org, "C", "5.00", null);
    UUID s1 = order(org, OrderStatus.CLOSED, "100.00", "10.00", "90.00", D1);
    costedLine(s1, a, 5, "20.00", "0", "12.00");
    UUID s2 = order(org, OrderStatus.FULFILLED, "95.00", "0.00", "95.00", D2);
    costedLine(s2, b, 3, "30.00", "0", "10.00");
    costedLine(s2, c, 1, "5.00", "0", null);
    // Never counted: not money-committed.
    UUID pending = order(org, OrderStatus.PENDING_PAYMENT, "999.00", "0.00", "999.00", D2);
    costedLine(pending, a, 99, "20.00", "0", "12.00");
    UUID cancelled = order(org, OrderStatus.CANCELLED, "999.00", "0.00", "999.00", D2);
    costedLine(cancelled, b, 99, "30.00", "0", "10.00");

    ProfitTotals t = reports.profit(org, FROM, TO).totals();
    List<TopProduct> rows = reports.topProducts(org, FROM, TO, "revenue", "50").items();
    assertEquals(rows.stream().mapToLong(TopProduct::quantity).sum(), t.quantity());
    assertEquals(rows.stream().mapToLong(TopProduct::costedQuantity).sum(), t.costedQuantity());
    assertEquals(0, sum(rows, TopProduct::costedNetSales).compareTo(t.costedNetSales()));
    assertEquals(0, sum(rows, TopProduct::cost).compareTo(t.cost()));
    assertEquals(0, sum(rows, TopProduct::grossProfit).compareTo(t.grossProfit()));
    // The numbers themselves: A net 100−10=90, cost 60 → 30; B net 90, cost 30 → 60.
    assertEquals(9, t.quantity());
    assertEquals(8, t.costedQuantity());
    assertMoney("180.00", t.costedNetSales(), "Σ costed net sales");
    assertMoney("90.00", t.cost(), "Σ cost");
    assertMoney("90.00", t.grossProfit(), "Σ profit");

    ProfitTotals empty =
        reports.profit(org, "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z").totals();
    assertEquals(0, empty.quantity());
    assertEquals(0, empty.costedQuantity());
    assertNull(empty.costedNetSales());
    assertNull(empty.cost());
    assertNull(empty.grossProfit());
  }

  private static BigDecimal sum(
      List<TopProduct> rows, java.util.function.Function<TopProduct, BigDecimal> f) {
    return rows.stream().map(f).filter(x -> x != null).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  // org isolation

  @Test
  void reports_areOrgScoped() {
    UUID a = createOrg("acme");
    UUID b = createOrg("beta");
    // Org A: one payment, one tracked product.
    payment(a, "100.00", D1);
    UUID pa = product(a, "A-prod", "10.00");
    inventory(a, pa, 3); // retail 30
    // Org B noise that must never bleed into A's figures.
    payment(b, "9999.00", D1);
    invoice(b, InvoiceStatus.ISSUED, "9999.00", "0.00", D1);
    UUID pb = product(b, "B-prod", "999.00");
    inventory(b, pb, 100);

    RevenueReport rev = reports.revenue(a, FROM, TO, "day");
    assertEquals(0, new BigDecimal("100.00").compareTo(rev.totalCollected()));
    assertEquals(0, BigDecimal.ZERO.compareTo(rev.totalInvoiced()));

    InventoryValuationReport val = reports.inventoryValuation(a);
    assertEquals(1, val.valuation().trackedProducts());
    assertEquals(0, new BigDecimal("30.00").compareTo(val.valuation().retailValue()));
  }

  // seed helpers

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  private UUID product(UUID org, String name, String basePrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, name)
        .set(PRODUCT.SKU, "SKU-" + seq.getAndIncrement())
        .set(PRODUCT.BASE_PRICE, new BigDecimal(basePrice))
        .execute();
    return id;
  }

  private void inventory(UUID org, UUID productId, int stockQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.PRODUCT_ID, productId)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .execute();
  }

  private UUID order(
      UUID org,
      OrderChannel channel,
      OrderStatus status,
      String grandTotal,
      OffsetDateTime placedAt) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, id)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-" + seq.getAndIncrement())
        .set(SALES_ORDER.CHANNEL, channel)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.PLACED_AT, placedAt)
        .execute();
    return id;
  }

  private void line(UUID orderId, UUID productId, int qty, String lineTotal) {
    BigDecimal total = new BigDecimal(lineTotal);
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, UUID.randomUUID())
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, productId)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, total)
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, total)
        .set(SALES_ORDER_LINE.LINE_TOTAL, total)
        .execute();
  }

  private UUID product(UUID org, String name, String basePrice, String costPrice) {
    UUID id = product(org, name, basePrice);
    dsl.update(PRODUCT)
        .set(PRODUCT.COST_PRICE, costPrice == null ? null : new BigDecimal(costPrice))
        .where(PRODUCT.ID.eq(id))
        .execute();
    return id;
  }

  /** An order whose goods subtotal and order-level discount are set — what proration reads. */
  private UUID order(
      UUID org,
      OrderStatus status,
      String subtotal,
      String discountTotal,
      String grandTotal,
      OffsetDateTime placedAt) {
    UUID id = order(org, OrderChannel.IN_STORE, status, grandTotal, placedAt);
    dsl.update(SALES_ORDER)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(subtotal))
        .set(SALES_ORDER.DISCOUNT_TOTAL, new BigDecimal(discountTotal))
        .where(SALES_ORDER.ID.eq(id))
        .execute();
    return id;
  }

  /**
   * A line with a real price/tax split and an optional cost snapshot — the V92 shape. {@code
   * unitCost == null} models a sale placed before the product was costed.
   */
  private void costedLine(
      UUID orderId, UUID productId, int qty, String unitPrice, String taxRate, String unitCost) {
    BigDecimal price = new BigDecimal(unitPrice);
    BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty)).setScale(2);
    BigDecimal tax = subtotal.multiply(new BigDecimal(taxRate)).setScale(2, RoundingMode.HALF_EVEN);
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, UUID.randomUUID())
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, productId)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, price)
        .set(SALES_ORDER_LINE.UNIT_COST, unitCost == null ? null : new BigDecimal(unitCost))
        .set(SALES_ORDER_LINE.TAX_RATE, new BigDecimal(taxRate))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, subtotal)
        .set(SALES_ORDER_LINE.LINE_TAX, tax)
        .set(SALES_ORDER_LINE.LINE_TOTAL, subtotal.add(tax))
        .execute();
  }

  private static void assertMoney(String expected, BigDecimal actual, String what) {
    assertNotNull(actual, what + " must be present");
    assertEquals(0, new BigDecimal(expected).compareTo(actual), what + " = " + actual);
  }

  /** Every invoice needs its own fulfillment (fulfillment_id is UNIQUE NOT NULL). */
  private void invoice(
      UUID org,
      InvoiceStatus status,
      String grandTotal,
      String paidAmount,
      OffsetDateTime issuedAt) {
    UUID orderId = order(org, OrderChannel.ONLINE, OrderStatus.FULFILLING, grandTotal, D1);
    UUID fulfillmentId = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillmentId)
        .set(FULFILLMENT.ORG_ID, org)
        .set(FULFILLMENT.SALES_ORDER_ID, orderId)
        .set(FULFILLMENT.STATUS, FulfillmentStatus.DELIVERED)
        .execute();
    dsl.insertInto(SALES_INVOICE)
        .set(SALES_INVOICE.ID, UUID.randomUUID())
        .set(SALES_INVOICE.ORG_ID, org)
        .set(SALES_INVOICE.SALES_ORDER_ID, orderId)
        .set(SALES_INVOICE.FULFILLMENT_ID, fulfillmentId)
        .set(SALES_INVOICE.INVOICE_NUMBER, "INV-" + seq.getAndIncrement())
        .set(SALES_INVOICE.STATUS, status)
        .set(SALES_INVOICE.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_INVOICE.PAID_AMOUNT, new BigDecimal(paidAmount))
        .set(SALES_INVOICE.CUSTOMER_NAME, "Customer")
        .set(SALES_INVOICE.ISSUED_AT, issuedAt)
        .execute();
  }

  private UUID payment(UUID org, String amount, OffsetDateTime receivedAt) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER, PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, receivedAt)
        .execute();
    UUID id = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, id)
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.UNALLOCATED_AMOUNT, BigDecimal.ZERO)
        .set(PAYMENT.STATUS, PaymentStatus.ALLOCATED)
        .set(PAYMENT.RECEIVED_AT, receivedAt)
        .execute();
    return id;
  }

  // A sentinel far outside every test window: the refund's backing payment is an FK requirement,
  // not part of any revenue assertion, so it must never fall inside a `collected` window.
  private static final OffsetDateTime FAR_PAST =
      OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  private void refund(UUID org, RefundStatus status, String amount, OffsetDateTime executedAt) {
    UUID paymentId = payment(org, amount, FAR_PAST); // backing payment (payment-backed refund)
    dsl.insertInto(REFUND)
        .set(REFUND.ID, UUID.randomUUID())
        .set(REFUND.ORG_ID, org)
        .set(REFUND.PAYMENT_ID, paymentId)
        .set(REFUND.AMOUNT, new BigDecimal(amount))
        .set(REFUND.STATUS, status)
        .set(REFUND.METHOD, PaymentProvider.instapay_manual)
        .set(REFUND.EXECUTED_AT, executedAt)
        .execute();
  }

  // assertion helpers

  private static void assertPoint(
      RevenuePoint p, OffsetDateTime period, String invoiced, String collected, String refunded) {
    assertEquals(period, p.period());
    assertEquals(0, new BigDecimal(invoiced).compareTo(p.invoiced()), "invoiced @ " + period);
    assertEquals(0, new BigDecimal(collected).compareTo(p.collected()), "collected @ " + period);
    assertEquals(0, new BigDecimal(refunded).compareTo(p.refunded()), "refunded @ " + period);
  }

  private static void assertSales(
      SalesPoint p, OffsetDateTime period, String channel, long orders, String gross) {
    assertEquals(period, p.period());
    assertEquals(channel, p.channel());
    assertEquals(orders, p.orders());
    assertEquals(0, new BigDecimal(gross).compareTo(p.gross()));
  }

  private static void assertBand(AgingBand b, String label, long count, String outstanding) {
    assertEquals(label, b.label());
    assertEquals(count, b.count(), "count @ " + label);
    assertEquals(
        0, new BigDecimal(outstanding).compareTo(b.outstanding()), "outstanding @ " + label);
  }

  @Test
  void revenue_collectedIncludesAllPaymentStatuses() {
    // Sanity that `collected` isn't accidentally status-filtered: a payment counts regardless.
    UUID org = createOrg("acme");
    payment(org, "40.00", D1);
    assertTrue(
        reports.revenue(org, FROM, TO, "day").totalCollected().compareTo(BigDecimal.ZERO) > 0);
  }
}
