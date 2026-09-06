package com.loai.inventory.api.dto;

import java.util.List;

/**
 * {@code GET /sales-orders} — the generic {@link PageResponse} envelope plus a {@code summary} of
 * the whole filtered set ({@code stories/order_filters.md}).
 *
 * <p>JSON shape: { "data": [ … ], "total": 12, "page": 0, "size": 20, "summary": { "outstanding":
 * 1240.00, "value": 18900.00 } }
 */
public final class OrderListResponse extends PageResponse<SalesOrderResponse> {

  private final OrderListSummaryResponse summary;

  public OrderListResponse(
      List<SalesOrderResponse> data,
      long total,
      int page,
      int size,
      OrderListSummaryResponse summary) {
    super(data, total, page, size);
    this.summary = summary;
  }

  public OrderListSummaryResponse getSummary() {
    return summary;
  }
}
