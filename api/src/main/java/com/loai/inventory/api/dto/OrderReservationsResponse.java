package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.model.SalesOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response for {@code GET /sales-orders/{id}/reservations} ({@code stories/inventory_reads.md}):
 * the order header (so the holds panel renders standalone) + every reservation for the order, all
 * statuses ({@code ACTIVE} / {@code CONSUMED} / {@code RELEASED}), oldest first. Mirrors {@link
 * OrderPaymentsResponse}. No pagination — the count is bounded by the order's line count.
 */
public class OrderReservationsResponse {

  private PaymentTransactionResponse.OrderSummary order;
  private List<Row> data;

  private OrderReservationsResponse() {}

  public static OrderReservationsResponse from(
      SalesOrder order, List<InventoryReservation> reservations, Map<UUID, String> productNames) {
    OrderReservationsResponse r = new OrderReservationsResponse();
    r.order = PaymentTransactionResponse.OrderSummary.from(order);
    r.data =
        reservations.stream()
            .map(res -> Row.from(res, productNames.get(res.getProductId())))
            .toList();
    return r;
  }

  public PaymentTransactionResponse.OrderSummary getOrder() {
    return order;
  }

  public List<Row> getData() {
    return data;
  }

  /** One reservation row: the hold + the (batch-loaded) product name. */
  public static class Row {
    private UUID id;
    private UUID productId;
    private String productName;
    private UUID salesOrderLineId;
    private int quantity;
    private ReservationStatus status;
    private OffsetDateTime expiresAt;
    private OffsetDateTime consumedAt;
    private OffsetDateTime releasedAt;
    private String releasedReason;
    private OffsetDateTime createdAt;

    private Row() {}

    static Row from(InventoryReservation res, String productName) {
      Row row = new Row();
      row.id = res.getId();
      row.productId = res.getProductId();
      row.productName = productName;
      row.salesOrderLineId = res.getSalesOrderLineId();
      row.quantity = res.getQuantity();
      row.status = res.getStatus();
      row.expiresAt = res.getExpiresAt();
      row.consumedAt = res.getConsumedAt();
      row.releasedAt = res.getReleasedAt();
      row.releasedReason = res.getReleasedReason();
      row.createdAt = res.getCreatedAt();
      return row;
    }

    public UUID getId() {
      return id;
    }

    public UUID getProductId() {
      return productId;
    }

    public String getProductName() {
      return productName;
    }

    public UUID getSalesOrderLineId() {
      return salesOrderLineId;
    }

    public int getQuantity() {
      return quantity;
    }

    public ReservationStatus getStatus() {
      return status;
    }

    public OffsetDateTime getExpiresAt() {
      return expiresAt;
    }

    public OffsetDateTime getConsumedAt() {
      return consumedAt;
    }

    public OffsetDateTime getReleasedAt() {
      return releasedAt;
    }

    public String getReleasedReason() {
      return releasedReason;
    }

    public OffsetDateTime getCreatedAt() {
      return createdAt;
    }
  }
}
