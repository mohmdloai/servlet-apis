package com.loai.inventory.api.dto;

import com.loai.inventory.service.ReportService.SalesReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/orgs/{orgId}/reports/sales} — per-{@code (bucket, channel)} order count + gross
 * over money-committed orders, ordered {@code period ASC, channel ASC}, plus the echoed {@code
 * channel} filter (omitted when unset) and overall totals. The client pivots the flat rows into a
 * stacked chart. See {@code stories/reporting_reads.md} §G2.
 */
public record SalesReportResponse(
    String bucket,
    OffsetDateTime from,
    OffsetDateTime to,
    String channel,
    List<Point> series,
    Totals totals) {

  public record Point(OffsetDateTime period, String channel, long orders, BigDecimal gross) {}

  public record Totals(long orders, BigDecimal gross) {}

  public static SalesReportResponse from(SalesReport r) {
    List<Point> series =
        r.series().stream()
            .map(p -> new Point(p.period(), p.channel(), p.orders(), p.gross()))
            .toList();
    return new SalesReportResponse(
        r.bucket(),
        r.from(),
        r.to(),
        r.channel(),
        series,
        new Totals(r.totalOrders(), r.totalGross()));
  }
}
