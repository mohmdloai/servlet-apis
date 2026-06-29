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
 * <p>Lifecycle: PENDING → SHIPPED → DELIVERED, with a SHIPPED → FAILED branch when a shipment never
 * arrives (and a post-failure {@code returnedAt} stamp once the goods come back).
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
  private OffsetDateTime returnedAt;
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
        null,
        now);
  }

  /**
   * Build a fulfillment created directly DELIVERED — the in-store checkout path. There is no
   * PENDING/SHIPPED phase: the customer takes the goods now, so the caller writes the stock
   * movements (no reservation to consume) in the same transaction. {@code shippedAt} stays null;
   * {@code deliveredAt} is stamped now. See {@code state-machines.md} B2.
   */
  public static Fulfillment createDelivered(
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
        FulfillmentStatus.DELIVERED,
        carrier,
        trackingNumber,
        notes,
        null,
        now,
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
      OffsetDateTime returnedAt,
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
        returnedAt,
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
      OffsetDateTime returnedAt,
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
    this.returnedAt = returnedAt;
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

  /**
   * Mark the fulfillment DELIVERED. Guards the SHIPPED precondition — delivery is the online-flow
   * invoice-issuance trigger and must fire exactly once. The caller issues the SalesInvoice and
   * auto-allocates prepayment in the same transaction. No stock effect: that happened at SHIPPED.
   */
  public void markDelivered(OffsetDateTime now) {
    if (this.status != FulfillmentStatus.SHIPPED) {
      throw new InvalidOrderTransitionException(
          "cannot deliver fulfillment " + id + " in status " + status + "; expected SHIPPED");
    }
    Objects.requireNonNull(now, "now required");
    this.status = FulfillmentStatus.DELIVERED;
    this.deliveredAt = now;
    this.updatedAt = now;
  }

  /**
   * Mark a SHIPPED fulfillment FAILED — the package never arrived (lost in transit, refused, or
   * returned to sender). Guards the SHIPPED precondition: failure is only meaningful once stock has
   * physically left the warehouse, and DELIVERED is terminal (a post-delivery problem is a
   * CreditNote, not a failure). No stock moves here: the goods are still out there. If they later
   * come back, the caller records that separately via {@link #markReturned}. See {@code
   * state-machines.md} machine B.
   */
  public void markFailed(String reason, OffsetDateTime now) {
    if (this.status != FulfillmentStatus.SHIPPED) {
      throw new InvalidOrderTransitionException(
          "cannot fail fulfillment " + id + " in status " + status + "; expected SHIPPED");
    }
    Objects.requireNonNull(now, "now required");
    this.status = FulfillmentStatus.FAILED;
    this.failedAt = now;
    this.failedReason = reason;
    this.updatedAt = now;
  }

  /**
   * Record that a FAILED fulfillment's goods physically returned to the warehouse — the caller
   * writes the {@code +stock} inventory movement in the same transaction. FAILED is terminal, so
   * this is not a status change: it stamps {@code returnedAt} and is idempotent — a second return
   * is rejected so stock can't be double-counted.
   */
  public void markReturned(OffsetDateTime now) {
    if (this.status != FulfillmentStatus.FAILED) {
      throw new InvalidOrderTransitionException(
          "cannot return fulfillment " + id + " in status " + status + "; expected FAILED");
    }
    if (this.returnedAt != null) {
      throw new InvalidOrderTransitionException(
          "fulfillment " + id + " goods already returned at " + returnedAt);
    }
    Objects.requireNonNull(now, "now required");
    this.returnedAt = now;
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

  public OffsetDateTime getReturnedAt() {
    return returnedAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
