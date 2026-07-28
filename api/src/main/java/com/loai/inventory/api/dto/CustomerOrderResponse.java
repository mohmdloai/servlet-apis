package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of a customer's order history ({@code GET /api/orgs/{orgId}/customers/{id}/orders}) — the
 * lean shape a history list needs: what it was, what state it is in, what it cost, and when.
 *
 * <p><b>Lean on purpose.</b> Lines, payments, fulfillments and invoices all have their own reads
 * off {@code /sales-orders/{id}}; shipping them per row would make a customer with fifty orders an
 * expensive page for information nobody reads in a list. The row links to the order.
 *
 * <p>{@code placedAt} is null for an order that never left draft; the client renders {@code
 * createdAt} in that case rather than a blank — but the sort key stays {@code placed_at DESC, id
 * DESC}, which is the repository's own ordering for the portal read this reuses.
 */
public record CustomerOrderResponse(
    UUID id,
    String orderNumber,
    String status,
    String currency,
    BigDecimal grandTotal,
    BigDecimal prepaidAmount,
    OffsetDateTime placedAt,
    OffsetDateTime createdAt) {

  public static CustomerOrderResponse from(SalesOrder o) {
    return new CustomerOrderResponse(
        o.getId(),
        o.getOrderNumber(),
        o.getStatus().name(),
        o.getCurrency(),
        o.getGrandTotal(),
        o.getPrepaidAmount(),
        o.getPlacedAt(),
        o.getCreatedAt());
  }
}
