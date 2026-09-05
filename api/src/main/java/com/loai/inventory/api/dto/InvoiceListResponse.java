package com.loai.inventory.api.dto;

import java.util.List;

/**
 * {@code GET /invoices} — the generic {@link PageResponse} envelope plus a {@code summary} of the
 * whole filtered set ({@code stories/invoice_filters.md}).
 *
 * <p>JSON shape: { "data": [ … ], "total": 12, "page": 0, "size": 20, "summary": { "outstanding":
 * 1920.00, "issued": 15360.00 } }
 */
public final class InvoiceListResponse extends PageResponse<InvoiceSummaryResponse> {

  private final InvoiceListSummaryResponse summary;

  public InvoiceListResponse(
      List<InvoiceSummaryResponse> data,
      long total,
      int page,
      int size,
      InvoiceListSummaryResponse summary) {
    super(data, total, page, size);
    this.summary = summary;
  }

  public InvoiceListSummaryResponse getSummary() {
    return summary;
  }
}
