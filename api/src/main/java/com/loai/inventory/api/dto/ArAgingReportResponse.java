package com.loai.inventory.api.dto;

import com.loai.inventory.service.ReportService.ArAgingReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/orgs/{orgId}/reports/ar-aging} — outstanding money on ISSUED, not-fully-paid
 * invoices bucketed by age, every band always present (zero-filled), plus totals. Credit notes do
 * <em>not</em> reduce outstanding here (they aren't payments). See {@code
 * stories/reporting_reads.md} §G4.
 */
public record ArAgingReportResponse(
    OffsetDateTime asOf, List<Band> buckets, long totalCount, BigDecimal totalOutstanding) {

  public record Band(String label, long count, BigDecimal outstanding) {}

  public static ArAgingReportResponse from(ArAgingReport r) {
    List<Band> bands =
        r.bands().stream().map(b -> new Band(b.label(), b.count(), b.outstanding())).toList();
    return new ArAgingReportResponse(r.asOf(), bands, r.totalCount(), r.totalOutstanding());
  }
}
