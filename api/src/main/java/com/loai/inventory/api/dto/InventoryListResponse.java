package com.loai.inventory.api.dto;

import java.util.List;

/**
 * {@code GET /inventory} — the generic {@link PageResponse} envelope plus a {@code summary} of the
 * whole filtered set ({@code stories/inventory_filters.md}).
 *
 * <p>JSON shape: { "data": [ … ], "total": 4, "page": 0, "size": 20, "summary": { "products": 4,
 * "units_on_hand": 209, "units_available": 209, "costed_products": 3, "cost_value": 6120.00 } }
 */
public final class InventoryListResponse extends PageResponse<InventoryOverviewRow> {

  private final InventoryListSummaryResponse summary;

  public InventoryListResponse(
      List<InventoryOverviewRow> data,
      long total,
      int page,
      int size,
      InventoryListSummaryResponse summary) {
    super(data, total, page, size);
    this.summary = summary;
  }

  public InventoryListSummaryResponse getSummary() {
    return summary;
  }
}
