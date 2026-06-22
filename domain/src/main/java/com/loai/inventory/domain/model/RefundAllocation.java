package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The negative-side mirror of a {@link PaymentAllocation}: it records that a {@link Refund} unwound
 * {@code amount} of a specific {@link PaymentAllocation}. Written only for CreditNote-backed
 * refunds; direct-from-Payment refunds (overpayments) have no underlying allocation to unwind and
 * so produce zero rows. See {@code sys-analysis/outbound/refund.md}.
 */
public final class RefundAllocation {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID refundId;
  private final UUID paymentAllocationId;
  private final BigDecimal amount;

  public static RefundAllocation create(
      UUID id, UUID refundId, UUID paymentAllocationId, BigDecimal amount, OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(refundId, "refundId required");
    Objects.requireNonNull(paymentAllocationId, "paymentAllocationId required");
    Objects.requireNonNull(amount, "amount required");
    Objects.requireNonNull(now, "now required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be > 0");
    }
    return new RefundAllocation(
        id, refundId, paymentAllocationId, amount.setScale(MONEY_SCALE, MONEY_ROUNDING));
  }

  public static RefundAllocation rehydrate(
      UUID id, UUID refundId, UUID paymentAllocationId, BigDecimal amount) {
    return new RefundAllocation(id, refundId, paymentAllocationId, amount);
  }

  private RefundAllocation(UUID id, UUID refundId, UUID paymentAllocationId, BigDecimal amount) {
    this.id = id;
    this.refundId = refundId;
    this.paymentAllocationId = paymentAllocationId;
    this.amount = amount;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRefundId() {
    return refundId;
  }

  public UUID getPaymentAllocationId() {
    return paymentAllocationId;
  }

  public BigDecimal getAmount() {
    return amount;
  }
}
