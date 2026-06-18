package com.loai.inventory.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One line of a {@link Fulfillment}: a quantity of a single {@code sales_order_line} being shipped,
 * linked back to the {@code inventory_reservation} row it will consume at SHIPPED. The reservation
 * link gives reservation → fulfillment traceability without recomputation.
 */
public final class FulfillmentLine {

  private final UUID id;
  private final UUID fulfillmentId;
  private final UUID salesOrderLineId;
  private final int quantity;
  private final UUID inventoryReservationId;

  /**
   * Build a fulfillment line. {@code inventoryReservationId} may be null for the in-store flow,
   * where stock was reserved and consumed in the same instant (no reservation row existed).
   */
  public static FulfillmentLine create(
      UUID id,
      UUID fulfillmentId,
      UUID salesOrderLineId,
      int quantity,
      UUID inventoryReservationId) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(fulfillmentId, "fulfillmentId required");
    Objects.requireNonNull(salesOrderLineId, "salesOrderLineId required");
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    return new FulfillmentLine(
        id, fulfillmentId, salesOrderLineId, quantity, inventoryReservationId);
  }

  public static FulfillmentLine rehydrate(
      UUID id,
      UUID fulfillmentId,
      UUID salesOrderLineId,
      int quantity,
      UUID inventoryReservationId) {
    return new FulfillmentLine(
        id, fulfillmentId, salesOrderLineId, quantity, inventoryReservationId);
  }

  private FulfillmentLine(
      UUID id,
      UUID fulfillmentId,
      UUID salesOrderLineId,
      int quantity,
      UUID inventoryReservationId) {
    this.id = id;
    this.fulfillmentId = fulfillmentId;
    this.salesOrderLineId = salesOrderLineId;
    this.quantity = quantity;
    this.inventoryReservationId = inventoryReservationId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public UUID getSalesOrderLineId() {
    return salesOrderLineId;
  }

  public int getQuantity() {
    return quantity;
  }

  public UUID getInventoryReservationId() {
    return inventoryReservationId;
  }
}
