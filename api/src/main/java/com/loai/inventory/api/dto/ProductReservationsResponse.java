package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.repository.InventoryReservationRepository.ProductReservationRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /inventory/{productId}/reservations?status=} ({@code
 * stories/inventory_reads.md}): the product's holds in the requested status ({@code ACTIVE} by
 * default), each carrying order context ({@code sales_order_id} + {@code sales_order_number}) so
 * the stock detail can itemise {@code reserved_qty} and deep-link each hold to its order. No
 * pagination.
 */
public class ProductReservationsResponse {

  private List<Row> data;

  private ProductReservationsResponse() {}

  public static ProductReservationsResponse from(List<ProductReservationRow> rows) {
    ProductReservationsResponse r = new ProductReservationsResponse();
    r.data = rows.stream().map(Row::from).toList();
    return r;
  }

  public List<Row> getData() {
    return data;
  }

  /** One hold with its order context. */
  public static class Row {
    private UUID id;
    private UUID salesOrderId;
    private String salesOrderNumber;
    private UUID salesOrderLineId;
    private int quantity;
    private ReservationStatus status;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;

    private Row() {}

    static Row from(ProductReservationRow row) {
      InventoryReservation res = row.reservation();
      Row r = new Row();
      r.id = res.getId();
      r.salesOrderId = row.salesOrderId();
      r.salesOrderNumber = row.salesOrderNumber();
      r.salesOrderLineId = res.getSalesOrderLineId();
      r.quantity = res.getQuantity();
      r.status = res.getStatus();
      r.expiresAt = res.getExpiresAt();
      r.createdAt = res.getCreatedAt();
      return r;
    }

    public UUID getId() {
      return id;
    }

    public UUID getSalesOrderId() {
      return salesOrderId;
    }

    public String getSalesOrderNumber() {
      return salesOrderNumber;
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

    public OffsetDateTime getCreatedAt() {
      return createdAt;
    }
  }
}
