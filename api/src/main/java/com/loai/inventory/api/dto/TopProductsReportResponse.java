package com.loai.inventory.api.dto;

import com.loai.inventory.service.ReportService.TopProductsReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/orgs/{orgId}/reports/top-products} — the capped, ranked top-N products by
 * revenue, quantity or profit over the window, with the echoed {@code by}/{@code limit}. Not a
 * {@code PageResponse}: a capped top-N is not a pageable collection. See {@code
 * stories/reporting_reads.md} §G3.
 *
 * <p>The four cost fields per row (stories/product_cost_and_margin.md) are MANAGER-plane: written
 * only with manager authority. {@code costedQuantity} is then always present; {@code
 * costedNetSales} / {@code cost} / {@code grossProfit} ride only when {@code costedQuantity > 0}
 * (the repository hands them over as null otherwise, and null is omitted) — a product nobody costed
 * never reads "made nothing". Below MANAGER a row is exactly {@code {product_id, name, sku,
 * quantity, revenue}}.
 */
public record TopProductsReportResponse(
    OffsetDateTime from, OffsetDateTime to, String by, int limit, List<Item> items) {

  public record Item(
      UUID productId,
      String name,
      String sku,
      long quantity,
      BigDecimal revenue,
      Long costedQuantity,
      BigDecimal costedNetSales,
      BigDecimal cost,
      BigDecimal grossProfit) {}

  /** The pre-V92 envelope: no cost figure crosses. */
  public static TopProductsReportResponse from(TopProductsReport r) {
    return from(r, false);
  }

  public static TopProductsReportResponse from(TopProductsReport r, boolean costVisible) {
    List<Item> items =
        r.items().stream()
            .map(
                p ->
                    new Item(
                        p.productId(),
                        p.name(),
                        p.sku(),
                        p.quantity(),
                        p.revenue(),
                        costVisible ? p.costedQuantity() : null,
                        costVisible ? p.costedNetSales() : null,
                        costVisible ? p.cost() : null,
                        costVisible ? p.grossProfit() : null))
            .toList();
    return new TopProductsReportResponse(r.from(), r.to(), r.by(), r.limit(), items);
  }
}
