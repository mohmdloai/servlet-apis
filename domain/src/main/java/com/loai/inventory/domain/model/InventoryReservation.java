package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A claim on stock by a pending SalesOrder line. Created at order placement (status ACTIVE),
 * released on cancel/expire, consumed at fulfillment SHIPPED. Slice 2 only writes ACTIVE rows;
 * later slices will add the {@code consume} / {@code release} mutators.
 */
public final class InventoryReservation {

  private final UUID id;
  private final UUID orgId;
  private final UUID productId;
  private final UUID salesOrderLineId;
  private final int quantity;
  private final OffsetDateTime expiresAt;
  private final OffsetDateTime createdAt;

  private ReservationStatus status;
  private OffsetDateTime consumedAt;
  private OffsetDateTime releasedAt;
  private String releasedReason;

  /** Build a fresh ACTIVE reservation at order placement. */
  public static InventoryReservation createActive(
      UUID id,
      UUID orgId,
      UUID productId,
      UUID salesOrderLineId,
      int quantity,
      OffsetDateTime expiresAt,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(productId, "productId required");
    Objects.requireNonNull(salesOrderLineId, "salesOrderLineId required");
    Objects.requireNonNull(now, "now required");
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    return new InventoryReservation(
        id,
        orgId,
        productId,
        salesOrderLineId,
        quantity,
        expiresAt,
        now,
        ReservationStatus.ACTIVE,
        null,
        null,
        null);
  }

  /** Reconstruct from a persisted row; covers ACTIVE / CONSUMED / RELEASED. */
  public static InventoryReservation rehydrate(
      UUID id,
      UUID orgId,
      UUID productId,
      UUID salesOrderLineId,
      int quantity,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt,
      ReservationStatus status,
      OffsetDateTime consumedAt,
      OffsetDateTime releasedAt,
      String releasedReason) {
    return new InventoryReservation(
        id,
        orgId,
        productId,
        salesOrderLineId,
        quantity,
        expiresAt,
        createdAt,
        status,
        consumedAt,
        releasedAt,
        releasedReason);
  }

  private InventoryReservation(
      UUID id,
      UUID orgId,
      UUID productId,
      UUID salesOrderLineId,
      int quantity,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt,
      ReservationStatus status,
      OffsetDateTime consumedAt,
      OffsetDateTime releasedAt,
      String releasedReason) {
    this.id = id;
    this.orgId = orgId;
    this.productId = productId;
    this.salesOrderLineId = salesOrderLineId;
    this.quantity = quantity;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.status = status;
    this.consumedAt = consumedAt;
    this.releasedAt = releasedAt;
    this.releasedReason = releasedReason;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getProductId() {
    return productId;
  }

  public UUID getSalesOrderLineId() {
    return salesOrderLineId;
  }

  public int getQuantity() {
    return quantity;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public ReservationStatus getStatus() {
    return status;
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
}
