package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.loai.inventory.domain.model.report.TopProductSort;
import com.loai.inventory.domain.repository.ReportRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ReportService} — the parameter-validation/defaulting layer. Every 400 in
 * {@code stories/reporting_reads.md} §Errors originates here; these tests pin each one without a
 * database, and pin the defaults (window, bucket, limit, by, aging edges) by capturing what the
 * service forwards to a fake repository. See {@code stories/reporting_reads.md} §Tests.
 */
class ReportServiceTest {

  private static final UUID ORG = UUID.randomUUID();

  /** A fake repository that records the arguments the service forwarded, and returns empties. */
  private static final class CapturingRepo implements ReportRepository {
    String bucket;
    OffsetDateTime from;
    OffsetDateTime to;
    String channel;
    TopProductSort sort;
    Integer limit;
    boolean profitCalled;
    int[] edges;

    @Override
    public List<RevenuePoint> revenue(
        UUID orgId, String bucket, OffsetDateTime from, OffsetDateTime to) {
      this.bucket = bucket;
      this.from = from;
      this.to = to;
      return List.of();
    }

    @Override
    public List<SalesPoint> sales(
        UUID orgId, String bucket, OffsetDateTime from, OffsetDateTime to, String channel) {
      this.bucket = bucket;
      this.from = from;
      this.to = to;
      this.channel = channel;
      return List.of();
    }

    @Override
    public List<TopProduct> topProducts(
        UUID orgId, OffsetDateTime from, OffsetDateTime to, TopProductSort sort, int limit) {
      this.from = from;
      this.to = to;
      this.sort = sort;
      this.limit = limit;
      return List.of();
    }

    @Override
    public ProfitTotals profit(UUID orgId, OffsetDateTime from, OffsetDateTime to) {
      this.from = from;
      this.to = to;
      this.profitCalled = true;
      return new ProfitTotals(0, 0, null, null, null);
    }

    @Override
    public List<AgingBand> arAging(UUID orgId, int[] edges, OffsetDateTime asOf) {
      this.edges = edges;
      return List.of();
    }

    @Override
    public InventoryValuation inventoryValuation(UUID orgId) {
      return new InventoryValuation(0, 0, BigDecimal.ZERO, 0);
    }
  }

  private final CapturingRepo repo = new CapturingRepo();
  private final ReportService service = new ReportService(repo);

  // defaults

  @Test
  void revenue_defaults_are30dDayWindowEndingNow() {
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    service.revenue(ORG, null, null, null);
    assertEquals("day", repo.bucket);
    // to ≈ now, from = to − 30d.
    assertTrue(!repo.to.isBefore(before), "to should be ~now");
    assertEquals(30, Duration.between(repo.from, repo.to).toDays());
  }

  @Test
  void topProducts_defaults_revenueLimit10() {
    var report = service.topProducts(ORG, null, null, null, null);
    assertEquals(TopProductSort.REVENUE, repo.sort);
    assertEquals("revenue", report.by());
    assertEquals(10, repo.limit);
  }

  @Test
  void arAging_default_edges_30_60_90() {
    service.arAging(ORG, null);
    assertArrayEquals(new int[] {30, 60, 90}, repo.edges);
  }

  @Test
  void sales_blankChannel_isNullFilter() {
    service.sales(ORG, null, null, null, "  ");
    assertNull(repo.channel);
  }

  // window validation

  @Test
  void window_fromAfterTo_is400() {
    assertThrows(
        ValidationException.class,
        () -> service.revenue(ORG, "2026-07-10T00:00:00Z", "2026-07-01T00:00:00Z", "day"));
  }

  @Test
  void window_fromEqualsTo_is400() {
    assertThrows(
        ValidationException.class,
        () -> service.revenue(ORG, "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z", "day"));
  }

  @Test
  void window_over366Days_is400() {
    assertThrows(
        ValidationException.class,
        () -> service.revenue(ORG, "2025-01-01T00:00:00Z", "2026-06-01T00:00:00Z", "day"));
  }

  @Test
  void window_exactly366Days_isAllowed() {
    // 2024 is a leap year → 366 days, the boundary; must be accepted (cap is "> 366").
    service.revenue(ORG, "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z", "day");
    assertEquals(365, Duration.between(repo.from, repo.to).toDays());
    service.revenue(ORG, "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z", "day");
    assertEquals(366, Duration.between(repo.from, repo.to).toDays());
  }

  @Test
  void window_malformedDate_is400() {
    assertThrows(ValidationException.class, () -> service.revenue(ORG, "not-a-date", null, "day"));
  }

  // enum / limit / bucket validation

  @Test
  void bucket_unknown_is400() {
    assertThrows(ValidationException.class, () -> service.revenue(ORG, null, null, "hour"));
  }

  @Test
  void bucket_caseInsensitive() {
    service.revenue(ORG, null, null, "MONTH");
    assertEquals("month", repo.bucket);
  }

  @Test
  void channel_unknown_is400() {
    assertThrows(
        ValidationException.class, () -> service.sales(ORG, null, null, "day", "MARKETPLACE"));
  }

  @Test
  void channel_caseInsensitive() {
    service.sales(ORG, null, null, "day", "in_store");
    assertEquals("IN_STORE", repo.channel);
  }

  @Test
  void by_unknown_is400() {
    assertThrows(
        ValidationException.class, () -> service.topProducts(ORG, null, null, "margin", null));
  }

  @Test
  void by_quantity_sortsByQuantity() {
    var report = service.topProducts(ORG, null, null, "quantity", null);
    assertEquals(TopProductSort.QUANTITY, repo.sort);
    assertEquals("quantity", report.by());
  }

  @Test
  void by_profit_sortsByProfit_andEchoesIt() {
    var report = service.topProducts(ORG, null, null, " Profit ", null);
    assertEquals(TopProductSort.PROFIT, repo.sort);
    assertEquals("profit", report.by());
  }

  @Test
  void isProfitSort_recognisesOnlyProfit() {
    assertTrue(ReportService.isProfitSort("profit"));
    assertTrue(ReportService.isProfitSort(" PROFIT "));
    assertTrue(!ReportService.isProfitSort("revenue"));
    assertTrue(!ReportService.isProfitSort("margin"));
    assertTrue(!ReportService.isProfitSort(null));
  }

  @Test
  void profit_defaults_are30dWindowEndingNow() {
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    var report = service.profit(ORG, null, null);
    assertTrue(repo.profitCalled);
    assertTrue(!repo.to.isBefore(before), "to should be ~now");
    assertEquals(30, Duration.between(repo.from, repo.to).toDays());
    assertEquals(0, report.totals().quantity());
    assertNull(report.totals().grossProfit());
  }

  @Test
  void profit_windowRulesApply() {
    assertThrows(
        ValidationException.class,
        () -> service.profit(ORG, "2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z"));
    assertThrows(
        ValidationException.class,
        () -> service.profit(ORG, "2024-01-01T00:00:00Z", "2026-01-01T00:00:00Z"));
  }

  @Test
  void limit_belowOne_is400() {
    assertThrows(ValidationException.class, () -> service.topProducts(ORG, null, null, null, "0"));
  }

  @Test
  void limit_above50_is400() {
    assertThrows(ValidationException.class, () -> service.topProducts(ORG, null, null, null, "51"));
  }

  @Test
  void limit_nonInteger_is400() {
    assertThrows(
        ValidationException.class, () -> service.topProducts(ORG, null, null, null, "ten"));
  }

  @Test
  void limit_atCap50_isAllowed() {
    service.topProducts(ORG, null, null, null, "50");
    assertEquals(50, repo.limit);
  }

  // aging buckets validation

  @Test
  void agingBuckets_custom_parsed() {
    service.arAging(ORG, "15,45");
    assertArrayEquals(new int[] {15, 45}, repo.edges);
  }

  @Test
  void agingBuckets_nonAscending_is400() {
    assertThrows(ValidationException.class, () -> service.arAging(ORG, "60,30"));
  }

  @Test
  void agingBuckets_duplicate_is400() {
    // Equal adjacent edges are not strictly ascending.
    assertThrows(ValidationException.class, () -> service.arAging(ORG, "30,30"));
  }

  @Test
  void agingBuckets_nonPositive_is400() {
    assertThrows(ValidationException.class, () -> service.arAging(ORG, "0,30"));
    assertThrows(ValidationException.class, () -> service.arAging(ORG, "-5,30"));
  }

  @Test
  void agingBuckets_nonNumeric_is400() {
    assertThrows(ValidationException.class, () -> service.arAging(ORG, "30,x"));
  }

  @Test
  void agingBuckets_tooMany_is400() {
    assertThrows(ValidationException.class, () -> service.arAging(ORG, "10,20,30,40,50,60,70"));
  }
}
