package com.loai.inventory.service;

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
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The dashboard-analytics read service ({@code stories/reporting_reads.md}). It owns <em>parameter
 * validation and defaulting only</em> — window bounds, enum allowlists, band edges — and folds the
 * repository's series into totals; there are no business rules or transactions here. Every 400 in
 * the story's error table originates in this class, which is why the parsing lives here
 * (unit-tested without a database) rather than in the handler.
 */
public final class ReportService {

  /** Longest demo-able window; an unbounded scan is a self-inflicted outage. */
  static final long MAX_WINDOW_DAYS = 366;

  static final int DEFAULT_WINDOW_DAYS = 30;
  static final String DEFAULT_BUCKET = "day";
  private static final Set<String> BUCKETS = Set.of("day", "week", "month");
  private static final Set<String> CHANNELS = Set.of("ONLINE", "IN_STORE", "PHONE");

  static final int DEFAULT_TOP_LIMIT = 10;
  static final int MAX_TOP_LIMIT = 50;

  static final int[] DEFAULT_AGING_EDGES = {30, 60, 90};
  private static final int MAX_AGING_EDGES = 6;

  private final ReportRepository reports;

  public ReportService(ReportRepository reports) {
    this.reports = reports;
  }

  // G1 · revenue

  public RevenueReport revenue(UUID orgId, String fromParam, String toParam, String bucketParam) {
    Window w = window(fromParam, toParam);
    String bucket = bucket(bucketParam);
    List<RevenuePoint> series = reports.revenue(orgId, bucket, w.from(), w.to());

    BigDecimal invoiced = BigDecimal.ZERO;
    BigDecimal collected = BigDecimal.ZERO;
    BigDecimal refunded = BigDecimal.ZERO;
    for (RevenuePoint p : series) {
      invoiced = invoiced.add(p.invoiced());
      collected = collected.add(p.collected());
      refunded = refunded.add(p.refunded());
    }
    return new RevenueReport(bucket, w.from(), w.to(), series, invoiced, collected, refunded);
  }

  public record RevenueReport(
      String bucket,
      OffsetDateTime from,
      OffsetDateTime to,
      List<RevenuePoint> series,
      BigDecimal totalInvoiced,
      BigDecimal totalCollected,
      BigDecimal totalRefunded) {}

  // G2 · sales by channel

  public SalesReport sales(
      UUID orgId, String fromParam, String toParam, String bucketParam, String channelParam) {
    Window w = window(fromParam, toParam);
    String bucket = bucket(bucketParam);
    String channel = channel(channelParam);
    List<SalesPoint> series = reports.sales(orgId, bucket, w.from(), w.to(), channel);

    long orders = 0L;
    BigDecimal gross = BigDecimal.ZERO;
    for (SalesPoint p : series) {
      orders += p.orders();
      gross = gross.add(p.gross());
    }
    return new SalesReport(bucket, w.from(), w.to(), channel, series, orders, gross);
  }

  public record SalesReport(
      String bucket,
      OffsetDateTime from,
      OffsetDateTime to,
      String channel,
      List<SalesPoint> series,
      long totalOrders,
      BigDecimal totalGross) {}

  // G3 · top products

  public TopProductsReport topProducts(
      UUID orgId, String fromParam, String toParam, String byParam, String limitParam) {
    Window w = window(fromParam, toParam);
    TopProductSort sort = by(byParam);
    int limit = topLimit(limitParam);
    List<TopProduct> items = reports.topProducts(orgId, w.from(), w.to(), sort, limit);
    return new TopProductsReport(
        w.from(), w.to(), sort.name().toLowerCase(Locale.ROOT), limit, items);
  }

  public record TopProductsReport(
      OffsetDateTime from, OffsetDateTime to, String by, int limit, List<TopProduct> items) {}

  /**
   * Is this {@code ?by=} the MANAGER-plane sort? Answered here (the one place that parses the
   * parameter) so the handler can refuse it with a 403 before the query runs — an unknown value is
   * still the ordinary 400 from {@link #topProducts}, not a 403 that would confirm the option
   * exists.
   */
  public static boolean isProfitSort(String byParam) {
    return byParam != null && byParam.trim().equalsIgnoreCase("profit");
  }

  // G6 · profit (stories/product_cost_and_margin.md)

  /** The window's cost of goods + gross profit; common windowed-parameter rules verbatim. */
  public ProfitReport profit(UUID orgId, String fromParam, String toParam) {
    Window w = window(fromParam, toParam);
    return new ProfitReport(w.from(), w.to(), reports.profit(orgId, w.from(), w.to()));
  }

  public record ProfitReport(OffsetDateTime from, OffsetDateTime to, ProfitTotals totals) {}

  // G4 · AR aging

  public ArAgingReport arAging(UUID orgId, String bucketsParam) {
    int[] edges = agingEdges(bucketsParam);
    OffsetDateTime asOf = OffsetDateTime.now(ZoneOffset.UTC);
    List<AgingBand> bands = reports.arAging(orgId, edges, asOf);

    long totalCount = 0L;
    BigDecimal totalOutstanding = BigDecimal.ZERO;
    for (AgingBand b : bands) {
      totalCount += b.count();
      totalOutstanding = totalOutstanding.add(b.outstanding());
    }
    return new ArAgingReport(asOf, bands, totalCount, totalOutstanding);
  }

  public record ArAgingReport(
      OffsetDateTime asOf, List<AgingBand> bands, long totalCount, BigDecimal totalOutstanding) {}

  // G5 · inventory valuation

  public InventoryValuationReport inventoryValuation(UUID orgId) {
    return new InventoryValuationReport(
        OffsetDateTime.now(ZoneOffset.UTC), reports.inventoryValuation(orgId));
  }

  public record InventoryValuationReport(OffsetDateTime asOf, InventoryValuation valuation) {}

  // validation / defaulting

  private record Window(OffsetDateTime from, OffsetDateTime to) {}

  /**
   * Parse + validate the {@code from}/{@code to} window. Defaults: {@code to = now}, {@code from =
   * to − 30d}. Half-open {@code [from, to)}; {@code from >= to} → 400; span > 366d → 400.
   */
  private static Window window(String fromParam, String toParam) {
    OffsetDateTime to =
        toParam == null || toParam.isBlank()
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : parseTs("to", toParam);
    OffsetDateTime from =
        fromParam == null || fromParam.isBlank()
            ? to.minusDays(DEFAULT_WINDOW_DAYS)
            : parseTs("from", fromParam);
    if (!from.isBefore(to)) {
      throw new ValidationException("'from' must be strictly before 'to'");
    }
    if (Duration.between(from, to).toDays() > MAX_WINDOW_DAYS) {
      throw new ValidationException("window must not exceed " + MAX_WINDOW_DAYS + " days");
    }
    return new Window(from, to);
  }

  private static OffsetDateTime parseTs(String name, String value) {
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException e) {
      throw new ValidationException("'" + name + "' must be an ISO-8601 date-time");
    }
  }

  private static String bucket(String param) {
    if (param == null || param.isBlank()) {
      return DEFAULT_BUCKET;
    }
    String b = param.toLowerCase();
    if (!BUCKETS.contains(b)) {
      throw new ValidationException("'bucket' must be one of day, week, month");
    }
    return b;
  }

  private static String channel(String param) {
    if (param == null || param.isBlank()) {
      return null;
    }
    String c = param.toUpperCase();
    if (!CHANNELS.contains(c)) {
      throw new ValidationException("'channel' must be one of ONLINE, IN_STORE, PHONE");
    }
    return c;
  }

  private static TopProductSort by(String param) {
    if (param == null || param.isBlank()) {
      return TopProductSort.REVENUE;
    }
    String p = param.trim();
    if (p.equalsIgnoreCase("revenue")) {
      return TopProductSort.REVENUE;
    }
    if (p.equalsIgnoreCase("quantity")) {
      return TopProductSort.QUANTITY;
    }
    if (p.equalsIgnoreCase("profit")) {
      return TopProductSort.PROFIT;
    }
    throw new ValidationException("'by' must be revenue, quantity or profit");
  }

  private static int topLimit(String param) {
    if (param == null || param.isBlank()) {
      return DEFAULT_TOP_LIMIT;
    }
    int limit;
    try {
      limit = Integer.parseInt(param.trim());
    } catch (NumberFormatException e) {
      throw new ValidationException("'limit' must be an integer");
    }
    if (limit < 1 || limit > MAX_TOP_LIMIT) {
      throw new ValidationException("'limit' must be between 1 and " + MAX_TOP_LIMIT);
    }
    return limit;
  }

  private static int[] agingEdges(String param) {
    if (param == null || param.isBlank()) {
      return DEFAULT_AGING_EDGES.clone();
    }
    String[] parts = param.split(",", -1);
    if (parts.length < 1 || parts.length > MAX_AGING_EDGES) {
      throw new ValidationException("'buckets' must be 1 to " + MAX_AGING_EDGES + " day-edges");
    }
    int[] edges = new int[parts.length];
    int prev = 0;
    for (int i = 0; i < parts.length; i++) {
      int edge;
      try {
        edge = Integer.parseInt(parts[i].trim());
      } catch (NumberFormatException e) {
        throw new ValidationException("'buckets' must be comma-separated integers");
      }
      // Strictly ascending and positive — each edge must exceed the previous (and 0).
      if (edge <= prev) {
        throw new ValidationException("'buckets' must be positive and strictly ascending");
      }
      edges[i] = edge;
      prev = edge;
    }
    return edges;
  }
}
