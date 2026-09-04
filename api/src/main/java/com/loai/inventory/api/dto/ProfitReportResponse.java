package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.report.ProfitTotals;
import com.loai.inventory.service.ReportService.ProfitReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code GET /api/orgs/{orgId}/reports/profit?from=&to=} — the window's cost of goods and gross
 * profit in one row (stories/product_cost_and_margin.md). MANAGER-plane: the handler refuses the
 * whole read below manager authority, so nothing here is conditional on the caller. {@code
 * quantity} and {@code costedQuantity} are always present; the three money fields are over the
 * costed lines only and absent when {@code costedQuantity == 0} — the client's margin is {@code
 * gross_profit / costed_net_sales}, its coverage {@code costed_quantity / quantity}.
 */
public record ProfitReportResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    long quantity,
    long costedQuantity,
    BigDecimal costedNetSales,
    BigDecimal cost,
    BigDecimal grossProfit) {

  public static ProfitReportResponse from(ProfitReport r) {
    ProfitTotals t = r.totals();
    return new ProfitReportResponse(
        r.from(),
        r.to(),
        t.quantity(),
        t.costedQuantity(),
        t.costedNetSales(),
        t.cost(),
        t.grossProfit());
  }
}
