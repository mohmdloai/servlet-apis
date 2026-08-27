package com.loai.inventory.api.dto;

import com.loai.inventory.service.CounterReturnService;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/orgs/{orgId}/sales-orders/{id}/return} — every aggregate the one transaction
 * produced: the credit note (SETTLED for a cash sale, ISSUED for a transfer), the refund (EXECUTED
 * or PENDING) and the stock moves (empty when the goods stayed out of stock). The same body comes
 * back with {@code 200} on a replay.
 */
public record CounterReturnResponse(
    CreditNoteResponse creditNote, RefundResponse refund, List<Stock> stock) {

  public record Stock(UUID productId, int quantity, int stockAfter) {}

  public static CounterReturnResponse from(CounterReturnService.Returned r) {
    return new CounterReturnResponse(
        CreditNoteResponse.from(r.creditNote(), r.lines()),
        RefundResponse.from(r.refund()),
        r.stock().stream()
            .map(m -> new Stock(m.productId(), m.quantity(), m.stockAfter()))
            .toList());
  }
}
