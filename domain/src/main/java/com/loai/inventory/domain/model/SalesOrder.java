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
  private BigDecimal shippingTotal;
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

  /**
   * The delivery contact for <em>this order</em>, frozen at placement (V80): who receives it, the
   * number a courier calls, and where it goes.
   *
   * <p><b>Deliberately not the customer's own contact details.</b> A shopper sending a gift is
   * still themselves — {@code customer.phone} is the identity a notification channel dials (V79),
   * and before this snapshot existed the delivery contact was merged onto that row, so a gift order
   * redirected the buyer's own order updates to the recipient. One field cannot be both.
   *
   * <p>A copy, never a reference: the address-book row it came from may later be edited or deleted,
   * and this must not move with it — the same freezing rule as {@code sales_order_line.unitPrice}
   * and the invoice's contact block. All three are null for an IN_STORE sale (nothing is delivered)
   * and for every order placed before V80.
   */
  private String deliveryRecipient;

  private String deliveryPhone;
  private String deliveryAddress;

  /**
   * The coupon this order redeemed (roadmap item 9) — the id for the redemption count and FK
   * integrity, plus a FROZEN {@code couponCode} snapshot for display. Both null on an order placed
   * without a code, which is every order before V72 — nothing downstream may require them.
   */
  private UUID couponId;

  private String couponCode;

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
      BigDecimal shippingTotal,
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
        shippingTotal,
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
      BigDecimal shippingTotal,
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
    this.shippingTotal = shippingTotal;
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
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal shippingTotal,
      BigDecimal discountTotal,
      OffsetDateTime now) {
    requireStatus(OrderStatus.DRAFT);
    Objects.requireNonNull(subtotal, "subtotal required");
    Objects.requireNonNull(taxTotal, "taxTotal required");
    Objects.requireNonNull(shippingTotal, "shippingTotal required");
    Objects.requireNonNull(discountTotal, "discountTotal required");
    Objects.requireNonNull(now, "now required");
    if (subtotal.signum() < 0
        || taxTotal.signum() < 0
        || shippingTotal.signum() < 0
        || discountTotal.signum() < 0) {
      throw new IllegalArgumentException("money fields must be >= 0");
    }
    if (discountTotal.compareTo(subtotal) > 0) {
      throw new IllegalArgumentException("discount cannot exceed subtotal");
    }
    this.subtotal = subtotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.taxTotal = taxTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.shippingTotal = shippingTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.discountTotal = discountTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.grandTotal =
        this.subtotal.add(this.taxTotal).add(this.shippingTotal).subtract(this.discountTotal);
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

  /**
   * Record a partial online prepayment: accumulate {@code prepaid_amount} while the order stays
   * PENDING_PAYMENT. Used by reconciliation for an UNDERPAID transfer — the partial is a refundable
   * {@link com.loai.inventory.domain.model.Payment} the customer can top up (chase) or have
   * refunded on cancel. The caller passes the NEW total prepaid (existing + this transfer); it must
   * stay strictly below {@code grand_total} — once it reaches grand_total the order is PAID via
   * {@link #markPaid}.
   */
  public void addPrepayment(BigDecimal prepaid, OffsetDateTime now) {
    requireStatus(OrderStatus.PENDING_PAYMENT);
    Objects.requireNonNull(prepaid, "prepaid required");
    Objects.requireNonNull(now, "now required");
    if (prepaid.signum() < 0) {
      throw new IllegalArgumentException("prepaid must be >= 0");
    }
    if (prepaid.compareTo(grandTotal) >= 0) {
      throw new InvalidOrderTransitionException(
          "prepaid " + prepaid + " >= grandTotal " + grandTotal + " — use markPaid");
    }
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

  /**
   * In-store fast path: PAID → CLOSED directly at checkout, skipping FULFILLING/FULFILLED. By the
   * time this fires the single checkout transaction has already issued + paid the invoice and
   * created the DELIVERED fulfillment, so there is no fulfillment phase left to observe (see {@code
   * state-machines.md} A2). Online orders must go through {@link #close} via FULFILLED instead.
   */
  public void closeInStore(OffsetDateTime now) {
    requireStatus(OrderStatus.PAID);
    requireChannel(OrderChannel.IN_STORE);
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
    // FULFILLING is cancellable — the partial-delivery cancel (salesOrder.md side-effects table:
    // "→ CANCELLED (post-PAID, partial delivery)"). The domain can't see the order's fulfillments,
    // so the one precondition that needs them — no SHIPPED (in-flight) fulfillment — is enforced by
    // OrderCancellationService before this transition runs. Delivered lines keep their invoice and
    // allocation (crediting returned goods stays on the CreditNote+Refund flow); the un-invoiced
    // remainder of the prepayment is direct-refunded by the service.
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

  /**
   * Attach the redeemed coupon while the order is still a DRAFT (roadmap item 9). Separate from
   * {@link #setTotals} on purpose: the totals are arithmetic, this is provenance — the frozen
   * record of *which* code produced that discount, which the order and every invoice derived from
   * it will keep quoting long after the coupon row could have changed or gone.
   */
  public void applyCoupon(UUID couponId, String couponCode, OffsetDateTime now) {
    requireStatus(OrderStatus.DRAFT);
    Objects.requireNonNull(couponId, "couponId required");
    Objects.requireNonNull(couponCode, "couponCode required");
    Objects.requireNonNull(now, "now required");
    this.couponId = couponId;
    this.couponCode = couponCode;
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

  /** The flat delivery fee frozen at placement (V68); 0 for IN_STORE and unconfigured orgs. */
  public BigDecimal getShippingTotal() {
    return shippingTotal;
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

  public String getDeliveryRecipient() {
    return deliveryRecipient;
  }

  public String getDeliveryPhone() {
    return deliveryPhone;
  }

  public String getDeliveryAddress() {
    return deliveryAddress;
  }

  /**
   * Freeze the delivery contact onto the order. Called once, at placement, before insert; there is
   * no re-address flow (changing where a parcel goes after it is placed is a different feature with
   * its own money and stock questions).
   */
  public void setDeliveryContact(String recipient, String phone, String address) {
    this.deliveryRecipient = recipient;
    this.deliveryPhone = phone;
    this.deliveryAddress = address;
  }

  public UUID getCouponId() {
    return couponId;
  }

  public void setCouponId(UUID couponId) {
    this.couponId = couponId;
  }

  public String getCouponCode() {
    return couponCode;
  }

  public void setCouponCode(String couponCode) {
    this.couponCode = couponCode;
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
