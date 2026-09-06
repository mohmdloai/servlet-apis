package com.loai.inventory.api.dto;

import java.util.List;

/**
 * {@code GET /payment-transactions} — the generic {@link PageResponse} envelope plus a {@code
 * summary} of the whole filtered set ({@code stories/transaction_filters.md}).
 *
 * <p>JSON shape: { "data": [ … ], "total": 12, "page": 0, "size": 20, "summary": { "money_in":
 * 14320.00, "money_out": 0.00 } }
 */
public final class TransactionListResponse extends PageResponse<PaymentTransactionResponse> {

  private final TransactionListSummaryResponse summary;

  public TransactionListResponse(
      List<PaymentTransactionResponse> data,
      long total,
      int page,
      int size,
      TransactionListSummaryResponse summary) {
    super(data, total, page, size);
    this.summary = summary;
  }

  public TransactionListSummaryResponse getSummary() {
    return summary;
  }
}
