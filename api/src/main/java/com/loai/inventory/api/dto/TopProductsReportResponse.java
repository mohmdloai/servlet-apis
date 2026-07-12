package com.loai.inventory.api.dto;

import com.loai.inventory.service.ReportService.TopProductsReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/orgs/{orgId}/reports/top-products} — the capped, ranked top-N products by revenue
 * or quantity over the window, with the echoed {@code by}/{@code limit}. Not a {@code
 * PageResponse}: a capped top-N is not a pageable collection. See {@code
 * stories/reporting_reads.md} §G3.
 */
public record TopProductsReportResponse(
    OffsetDateTime from, OffsetDateTime to, String by, int limit, List<Item> items) {

  public record Item(UUID productId, String name, String sku, long quantity, BigDecimal revenue) {}

  public static TopProductsReportResponse from(TopProductsReport r) {
    List<Item> items =
        r.items().stream()
            .map(p -> new Item(p.productId(), p.name(), p.sku(), p.quantity(), p.revenue()))
            .toList();
    return new TopProductsReportResponse(r.from(), r.to(), r.by(), r.limit(), items);
  }
}
