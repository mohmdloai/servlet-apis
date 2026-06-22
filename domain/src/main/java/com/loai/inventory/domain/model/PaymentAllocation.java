package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The immutable link between one {@link Payment} and one ISSUED {@link SalesInvoice}, carrying how
 * much of that payment was applied to that invoice. Created at delivery in the online flow, when
 * prepayment is auto-allocated FIFO to the freshly-issued invoice. See {@code
 * sys-analysis/outbound/paymentAllocation.md}.
 */
public final class PaymentAllocation {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final UUID paymentId;
  private final UUID salesInvoiceId;
  private final BigDecimal amount;
  private final OffsetDateTime receivedAt;
  private final OffsetDateTime createdAt;

  public static PaymentAllocation create(
      UUID id,
      UUID orgId,
      UUID paymentId,
      UUID salesInvoiceId,
      BigDecimal amount,
      OffsetDateTime receivedAt,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(paymentId, "paymentId required");
    Objects.requireNonNull(salesInvoiceId, "salesInvoiceId required");
    Objects.requireNonNull(amount, "amount required");
    Objects.requireNonNull(receivedAt, "receivedAt required");
    Objects.requireNonNull(now, "now required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be > 0");
    }
    return new PaymentAllocation(
        id,
        orgId,
        paymentId,
        salesInvoiceId,
        amount.setScale(MONEY_SCALE, MONEY_ROUNDING),
        receivedAt,
        now);
  }

  public static PaymentAllocation rehydrate(
      UUID id,
      UUID orgId,
      UUID paymentId,
      UUID salesInvoiceId,
      BigDecimal amount,
      OffsetDateTime receivedAt,
      OffsetDateTime createdAt) {
    return new PaymentAllocation(
        id, orgId, paymentId, salesInvoiceId, amount, receivedAt, createdAt);
  }

  private PaymentAllocation(
      UUID id,
      UUID orgId,
      UUID paymentId,
      UUID salesInvoiceId,
      BigDecimal amount,
      OffsetDateTime receivedAt,
      OffsetDateTime createdAt) {
    this.id = id;
    this.orgId = orgId;
    this.paymentId = paymentId;
    this.salesInvoiceId = salesInvoiceId;
    this.amount = amount;
    this.receivedAt = receivedAt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getSalesInvoiceId() {
    return salesInvoiceId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  /** When the parent {@link Payment} was received — the primary FIFO key for refund unwinding. */
  public OffsetDateTime getReceivedAt() {
    return receivedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
