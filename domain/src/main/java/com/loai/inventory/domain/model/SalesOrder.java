package com.loai.inventory.domain.model;

import com.loai.inventory.common.exception.InvalidOrderTransitionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public class SalesOrder {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final UUID customerId;
  private final String orderNumber;
  private final OrderChannel channel;
  private final String currency;
  private final String idempotencyKey;
  private final OffsetDateTime createdAt;

  private OrderStatus status;
  private BigDecimal subtotal;
  private BigDecimal taxTotal;
  private BigDecimal discountTotal;
  private BigDecimal grandTotal;
  private BigDecimal prepaidAmount;
  private OffsetDateTime updatedAt;
  private OffsetDateTime placedAt;
  private OffsetDateTime expiresAt;
  private OffsetDateTime cancelledAt;
  private OffsetDateTime fulfilledAt;
  private OffsetDateTime closedAt;
  private OffsetDateTime expiredAt;
  private String notes;

  public static SalesOrder createDraft(
      UUID id,
      UUID orgId,
      UUID customerId,
      String orderNumber,
      OrderChannel channel,
      String currency,
      String idempotencyKey,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    // customerId may be null for IN_STORE walk-in sales without a CRM record.
    Objects.requireNonNull(orderNumber, "orderNumber required");
    Objects.requireNonNull(channel, "channel required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(now, "now required");
    if (channel == OrderChannel.ONLINE && (idempotencyKey == null || idempotencyKey.isBlank())) {
      throw new IllegalArgumentException("idempotencyKey required for ONLINE channel");
    }
    BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
    return new SalesOrder(
        id,
        orgId,
        customerId,
        orderNumber,
        channel,
        currency,
        idempotencyKey,
        now,
        OrderStatus.DRAFT,
        zero,
        zero,
        zero,
        zero,
        zero,
        now,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Reconstitutes an order from persistent state. Called by the repository — trusts DB invariants
   * and skips creation-time validation. Do not use for creating new orders.
   */
  public static SalesOrder rehydrate(
      UUID id,
      UUID orgId,
      UUID customerId,
      String orderNumber,
      OrderChannel channel,
      String currency,
      String idempotencyKey,
      OffsetDateTime createdAt,
      OrderStatus status,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal discountTotal,
      BigDecimal grandTotal,
      BigDecimal prepaidAmount,
      OffsetDateTime updatedAt,
      OffsetDateTime placedAt,
      OffsetDateTime expiresAt,
      OffsetDateTime cancelledAt,
      OffsetDateTime fulfilledAt,
      OffsetDateTime closedAt,
      OffsetDateTime expiredAt,
      String notes) {
    return new SalesOrder(
        id,
        orgId,
        customerId,
        orderNumber,
        channel,
        currency,
        idempotencyKey,
        createdAt,
        status,
        subtotal,
        taxTotal,
        discountTotal,
        grandTotal,
        prepaidAmount,
        updatedAt,
        placedAt,
        expiresAt,
        cancelledAt,
        fulfilledAt,
        closedAt,
        expiredAt,
        notes);
  }

  private SalesOrder(
      UUID id,
      UUID orgId,
      UUID customerId,
      String orderNumber,
      OrderChannel channel,
      String currency,
      String idempotencyKey,
      OffsetDateTime createdAt,
      OrderStatus status,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal discountTotal,
      BigDecimal grandTotal,
      BigDecimal prepaidAmount,
      OffsetDateTime updatedAt,
      OffsetDateTime placedAt,
      OffsetDateTime expiresAt,
      OffsetDateTime cancelledAt,
      OffsetDateTime fulfilledAt,
      OffsetDateTime closedAt,
      OffsetDateTime expiredAt,
      String notes) {
    this.id = id;
    this.orgId = orgId;
    this.customerId = customerId;
    this.orderNumber = orderNumber;
    this.channel = channel;
    this.currency = currency;
    this.idempotencyKey = idempotencyKey;
    this.createdAt = createdAt;
    this.status = status;
    this.subtotal = subtotal;
    this.taxTotal = taxTotal;
    this.discountTotal = discountTotal;
    this.grandTotal = grandTotal;
    this.prepaidAmount = prepaidAmount;
    this.updatedAt = updatedAt;
    this.placedAt = placedAt;
    this.expiresAt = expiresAt;
    this.cancelledAt = cancelledAt;
    this.fulfilledAt = fulfilledAt;
    this.closedAt = closedAt;
    this.expiredAt = expiredAt;
    this.notes = notes;
  }

  public void setTotals(
      BigDecimal subtotal, BigDecimal taxTotal, BigDecimal discountTotal, OffsetDateTime now) {
    requireStatus(OrderStatus.DRAFT);
    Objects.requireNonNull(subtotal, "subtotal required");
    Objects.requireNonNull(taxTotal, "taxTotal required");
    Objects.requireNonNull(discountTotal, "discountTotal required");
    Objects.requireNonNull(now, "now required");
    if (subtotal.signum() < 0 || taxTotal.signum() < 0 || discountTotal.signum() < 0) {
      throw new IllegalArgumentException("money fields must be >= 0");
    }
    if (discountTotal.compareTo(subtotal) > 0) {
      throw new IllegalArgumentException("discount cannot exceed subtotal");
    }
    this.subtotal = subtotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.taxTotal = taxTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.discountTotal = discountTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.grandTotal = this.subtotal.add(this.taxTotal).subtract(this.discountTotal);
    this.updatedAt = now;
  }

  public void markPendingPayment(OffsetDateTime now, OffsetDateTime expiresAt) {
    requireStatus(OrderStatus.DRAFT);
    requireChannel(OrderChannel.ONLINE);
    Objects.requireNonNull(now, "now required");
    Objects.requireNonNull(expiresAt, "expiresAt required for PENDING_PAYMENT");
    if (grandTotal.signum() <= 0) {
      throw new InvalidOrderTransitionException("cannot place order with non-positive grandTotal");
    }
    this.status = OrderStatus.PENDING_PAYMENT;
    this.placedAt = now;
    this.expiresAt = expiresAt;
    this.updatedAt = now;
  }

  public void markPaid(BigDecimal prepaid, OffsetDateTime now) {
    // DRAFT → PAID is the IN_STORE / PHONE pay-on-place fast path (skips PENDING_PAYMENT).
    // PENDING_PAYMENT → PAID is the ONLINE path after payment verification.
    requireStatus(OrderStatus.DRAFT, OrderStatus.PENDING_PAYMENT);
    Objects.requireNonNull(prepaid, "prepaid required");
    Objects.requireNonNull(now, "now required");
    if (grandTotal.signum() <= 0) {
      throw new InvalidOrderTransitionException("cannot mark paid with non-positive grandTotal");
    }
    if (prepaid.signum() < 0) {
      throw new IllegalArgumentException("prepaid must be >= 0");
    }
    if (prepaid.compareTo(grandTotal) < 0) {
      throw new InvalidOrderTransitionException(
          "prepaid " + prepaid + " < grandTotal " + grandTotal);
    }
    if (this.status == OrderStatus.DRAFT) {
      this.placedAt = now;
    }
    this.status = OrderStatus.PAID;
    this.prepaidAmount = prepaid.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.updatedAt = now;
  }

  public void markFulfilling(OffsetDateTime now) {
    // ONLINE / PHONE flow only — IN_STORE goes PAID → CLOSED directly at checkout.
    requireStatus(OrderStatus.PAID);
    Objects.requireNonNull(now, "now required");
    this.status = OrderStatus.FULFILLING;
    this.updatedAt = now;
  }

  public void markFulfilled(OffsetDateTime now) {
    // ONLINE / PHONE flow only — IN_STORE goes PAID → CLOSED directly at checkout.
    requireStatus(OrderStatus.FULFILLING);
    Objects.requireNonNull(now, "now required");
    this.status = OrderStatus.FULFILLED;
    this.fulfilledAt = now;
    this.updatedAt = now;
  }

  public void close(OffsetDateTime now) {
    requireStatus(OrderStatus.FULFILLED);
    Objects.requireNonNull(now, "now required");
    this.status = OrderStatus.CLOSED;
    this.closedAt = now;
    this.updatedAt = now;
  }

  public void cancel(OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (status == OrderStatus.FULFILLED || status == OrderStatus.CLOSED) {
      throw new InvalidOrderTransitionException(
          "cannot cancel order in status " + status + " — issue a CreditNote instead");
    }
    if (status == OrderStatus.CANCELLED || status == OrderStatus.EXPIRED) {
      throw new InvalidOrderTransitionException("order is already terminal: " + status);
    }
    this.status = OrderStatus.CANCELLED;
    this.cancelledAt = now;
    this.updatedAt = now;
  }

  public void markExpired(OffsetDateTime now) {
    requireStatus(OrderStatus.PENDING_PAYMENT);
    Objects.requireNonNull(now, "now required");
    this.status = OrderStatus.EXPIRED;
    this.expiredAt = now;
    this.updatedAt = now;
  }

  public void updateNotes(String notes, OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    this.notes = notes;
    this.updatedAt = now;
  }

  private void requireStatus(OrderStatus... allowed) {
    for (OrderStatus s : allowed) {
      if (this.status == s) return;
    }
    throw new InvalidOrderTransitionException(
        "cannot transition from " + status + "; expected one of " + Arrays.toString(allowed));
  }

  private void requireChannel(OrderChannel expected) {
    if (this.channel != expected) {
      throw new InvalidOrderTransitionException(
          "operation requires channel " + expected + " but was " + channel);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public OrderChannel getChannel() {
    return channel;
  }

  public String getCurrency() {
    return currency;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getDiscountTotal() {
    return discountTotal;
  }

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public BigDecimal getPrepaidAmount() {
    return prepaidAmount;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public OffsetDateTime getPlacedAt() {
    return placedAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public OffsetDateTime getCancelledAt() {
    return cancelledAt;
  }

  public OffsetDateTime getFulfilledAt() {
    return fulfilledAt;
  }

  public OffsetDateTime getClosedAt() {
    return closedAt;
  }

  public OffsetDateTime getExpiredAt() {
    return expiredAt;
  }

  public String getNotes() {
    return notes;
  }

  @Override
  public String toString() {
    return "SalesOrder{id="
        + id
        + ", orgId="
        + orgId
        + ", orderNumber='"
        + orderNumber
        + "', channel="
        + channel
        + ", status="
        + status
        + ", grandTotal="
        + grandTotal
        + "}";
  }
}
