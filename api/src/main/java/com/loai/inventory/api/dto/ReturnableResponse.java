package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.service.CounterReturnService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/orgs/{orgId}/sales-orders/{id}/returnable} — what a receipt can still give back
 * ({@code stories/counter_return.md}). {@code unit_refund} is the prorated net per unit for
 * display; the POST recomputes with the remainder rule and is the authority. {@code tenders} and
 * {@code cash_refundable} ({@code stories/split_tender.md}) let the sheet say per quantity which
 * way a {@code SPLIT} sale's refund will go — the POST decides.
 */
public record ReturnableResponse(
    Order order,
    UUID salesInvoiceId,
    String tender,
    String refundMode,
    List<Tender> tenders,
    BigDecimal cashRefundable,
    List<Line> lines) {

  public record Order(UUID id, String orderNumber, OrderChannel channel, OrderStatus status) {}

  public record Tender(String provider, BigDecimal amount) {}

  public record Line(
      UUID productId,
      String description,
      BigDecimal unitPrice,
      BigDecimal taxRate,
      int billed,
      int returned,
      int returnable,
      BigDecimal unitRefund) {}

  public static ReturnableResponse from(CounterReturnService.Returnable r) {
    return new ReturnableResponse(
        new Order(
            r.order().getId(),
            r.order().getOrderNumber(),
            r.order().getChannel(),
            r.order().getStatus()),
        r.invoice().getId(),
        r.tender().name(),
        r.refundMode().name(),
        r.tenders().stream().map(t -> new Tender(t.provider().name(), t.amount())).toList(),
        r.cashRefundable(),
        r.lines().stream()
            .map(
                l ->
                    new Line(
                        l.productId(),
                        l.description(),
                        l.unitPrice(),
                        l.taxRate(),
                        l.billed(),
                        l.returned(),
                        l.returnable(),
                        l.unitRefund()))
            .toList());
  }
}
