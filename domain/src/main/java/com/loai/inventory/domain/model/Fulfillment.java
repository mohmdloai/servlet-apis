package com.loai.inventory.domain.model;

import com.loai.inventory.common.exception.InvalidOrderTransitionException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A shipment of part (or all) of a paid SalesOrder. Created PENDING when staff prepare to ship,
 * moved to SHIPPED once the goods physically leave the warehouse — the SHIPPED transition is where
 * stock actually decrements (reservations consumed, {@code inventory.on_hand} dropped). See {@code
 * sys-analysis/outbound/fulfillment.md}.
 *
 * <p>This slice models the PENDING → SHIPPED transition only; DELIVERED / CANCELLED / FAILED arrive
 * in later slices.
 */
public final class Fulfillment {

  private final UUID id;
  private final UUID orgId;
  private final UUID salesOrderId;
  private final OffsetDateTime createdAt;

  private FulfillmentStatus status;
  private String carrier;
  private String trackingNumber;
  private String notes;
  private OffsetDateTime shippedAt;
  private OffsetDateTime deliveredAt;
  private OffsetDateTime cancelledAt;
  private OffsetDateTime failedAt;
  private String failedReason;
  private OffsetDateTime updatedAt;

  /** Build a fresh PENDING fulfillment. Carrier / tracking / notes are optional at this stage. */
  public static Fulfillment createPending(
      UUID id,
      UUID orgId,
      UUID salesOrderId,
      String carrier,
      String trackingNumber,
      String notes,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(salesOrderId, "salesOrderId required");
    Objects.requireNonNull(now, "now required");
    return new Fulfillment(
        id,
        orgId,
        salesOrderId,
        now,
        FulfillmentStatus.PENDING,
        carrier,
        trackingNumber,
        notes,
        null,
        null,
        null,
        null,
        null,
        now);
  }

  /** Reconstruct from a persisted row. Trusts DB invariants; skips creation-time validation. */
  public static Fulfillment rehydrate(
      UUID id,
      UUID orgId,
      UUID salesOrderId,
      OffsetDateTime createdAt,
      FulfillmentStatus status,
      String carrier,
      String trackingNumber,
      String notes,
      OffsetDateTime shippedAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime cancelledAt,
      OffsetDateTime failedAt,
      String failedReason,
      OffsetDateTime updatedAt) {
    return new Fulfillment(
        id,
        orgId,
        salesOrderId,
        createdAt,
        status,
        carrier,
        trackingNumber,
        notes,
        shippedAt,
        deliveredAt,
        cancelledAt,
        failedAt,
        failedReason,
        updatedAt);
  }

  private Fulfillment(
      UUID id,
      UUID orgId,
      UUID salesOrderId,
      OffsetDateTime createdAt,
      FulfillmentStatus status,
      String carrier,
      String trackingNumber,
      String notes,
      OffsetDateTime shippedAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime cancelledAt,
      OffsetDateTime failedAt,
      String failedReason,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.salesOrderId = salesOrderId;
    this.createdAt = createdAt;
    this.status = status;
    this.carrier = carrier;
    this.trackingNumber = trackingNumber;
    this.notes = notes;
    this.shippedAt = shippedAt;
    this.deliveredAt = deliveredAt;
    this.cancelledAt = cancelledAt;
    this.failedAt = failedAt;
    this.failedReason = failedReason;
    this.updatedAt = updatedAt;
  }

  /**
   * Mark the fulfillment SHIPPED. Guards the PENDING precondition — the consequential stock-moving
   * transition must only fire once. The caller performs the inventory side effects in the same
   * transaction.
   */
  public void ship(OffsetDateTime now) {
    if (this.status != FulfillmentStatus.PENDING) {
      throw new InvalidOrderTransitionException(
          "cannot ship fulfillment " + id + " in status " + status + "; expected PENDING");
    }
    Objects.requireNonNull(now, "now required");
    this.status = FulfillmentStatus.SHIPPED;
    this.shippedAt = now;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public FulfillmentStatus getStatus() {
    return status;
  }

  public String getCarrier() {
    return carrier;
  }

  public String getTrackingNumber() {
    return trackingNumber;
  }

  public String getNotes() {
    return notes;
  }

  public OffsetDateTime getShippedAt() {
    return shippedAt;
  }

  public OffsetDateTime getDeliveredAt() {
    return deliveredAt;
  }

  public OffsetDateTime getCancelledAt() {
    return cancelledAt;
  }

  public OffsetDateTime getFailedAt() {
    return failedAt;
  }

  public String getFailedReason() {
    return failedReason;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
