package com.loai.inventory.api.dto;

import com.loai.inventory.service.ReportService.RevenueReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/orgs/{orgId}/reports/revenue} — sparse per-bucket invoiced/collected/refunded
 * plus window echo and overall totals. Refund <em>rate</em> is {@code totals.refunded /
 * totals.collected} computed client-side. Money serializes as the usual decimal-EGP number. See
 * {@code stories/reporting_reads.md} §G1.
 */
public record RevenueReportResponse(
    String bucket, OffsetDateTime from, OffsetDateTime to, List<Point> series, Totals totals) {

  public record Point(
      OffsetDateTime period, BigDecimal invoiced, BigDecimal collected, BigDecimal refunded) {}

  public record Totals(BigDecimal invoiced, BigDecimal collected, BigDecimal refunded) {}

  public static RevenueReportResponse from(RevenueReport r) {
    List<Point> series =
        r.series().stream()
            .map(p -> new Point(p.period(), p.invoiced(), p.collected(), p.refunded()))
            .toList();
    return new RevenueReportResponse(
        r.bucket(),
        r.from(),
        r.to(),
        series,
        new Totals(r.totalInvoiced(), r.totalCollected(), r.totalRefunded()));
  }
}
