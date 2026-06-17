package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Money received for an order, 1:1 with the underlying {@link PaymentTransaction}. Created on a
 * MATCHED reconciliation in {@code RECEIVED} state with {@code unallocatedAmount == amount}; for an
 * online prepayment that stays equal to {@code amount} until the order's invoice is issued at
 * delivery (a later slice).
 */
public class Payment {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final UUID customerId;
  private final UUID salesOrderId;
  private final UUID paymentTransactionId;
  private final BigDecimal amount;
  private final String currency;
  private final OffsetDateTime receivedAt;
  private final OffsetDateTime createdAt;

  private BigDecimal unallocatedAmount;
  private PaymentStatus status;
  private String notes;
  private OffsetDateTime updatedAt;

  /** A fully-unallocated payment in {@code RECEIVED} state, linked to its order and transaction. */
  public static Payment createReceived(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesOrderId,
      UUID paymentTransactionId,
      BigDecimal amount,
      String currency,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(salesOrderId, "salesOrderId required");
    Objects.requireNonNull(paymentTransactionId, "paymentTransactionId required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(now, "now required");
    Objects.requireNonNull(amount, "amount required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be > 0");
    }
    BigDecimal scaled = amount.setScale(MONEY_SCALE, MONEY_ROUNDING);
    return new Payment(
        id,
        orgId,
        customerId,
        salesOrderId,
        paymentTransactionId,
        scaled,
        currency,
        now,
        now,
        scaled,
        PaymentStatus.RECEIVED,
        null,
        now);
  }

  /** Reconstitute from persistent state — trusts DB invariants, skips creation-time validation. */
  public static Payment rehydrate(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesOrderId,
      UUID paymentTransactionId,
      BigDecimal amount,
      String currency,
      OffsetDateTime receivedAt,
      OffsetDateTime createdAt,
      BigDecimal unallocatedAmount,
      PaymentStatus status,
      String notes,
      OffsetDateTime updatedAt) {
    return new Payment(
        id,
        orgId,
        customerId,
        salesOrderId,
        paymentTransactionId,
        amount,
        currency,
        receivedAt,
        createdAt,
        unallocatedAmount,
        status,
        notes,
        updatedAt);
  }

  private Payment(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesOrderId,
      UUID paymentTransactionId,
      BigDecimal amount,
      String currency,
      OffsetDateTime receivedAt,
      OffsetDateTime createdAt,
      BigDecimal unallocatedAmount,
      PaymentStatus status,
      String notes,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.customerId = customerId;
    this.salesOrderId = salesOrderId;
    this.paymentTransactionId = paymentTransactionId;
    this.amount = amount;
    this.currency = currency;
    this.receivedAt = receivedAt;
    this.createdAt = createdAt;
    this.unallocatedAmount = unallocatedAmount;
    this.status = status;
    this.notes = notes;
    this.updatedAt = updatedAt;
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

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public UUID getPaymentTransactionId() {
    return paymentTransactionId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public OffsetDateTime getReceivedAt() {
    return receivedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public BigDecimal getUnallocatedAmount() {
    return unallocatedAmount;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public String getNotes() {
    return notes;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public String toString() {
    return "Payment{id="
        + id
        + ", salesOrderId="
        + salesOrderId
        + ", amount="
        + amount
        + ", unallocated="
        + unallocatedAmount
        + ", status="
        + status
        + "}";
  }
}
