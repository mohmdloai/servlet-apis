package com.loai.inventory.domain.model;

import com.loai.inventory.common.exception.InvalidOrderTransitionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The act of returning money to a customer, recorded as a DEBIT {@link PaymentTransaction} when
 * executed. Authorized by exactly one of two sources (DB CHECK enforces the xor):
 *
 * <ul>
 *   <li>{@code creditNoteId} — returns / cancellations / pricing errors / goodwill / dispute
 *       resolution. Unwinds the credited invoice's {@link PaymentAllocation}s via {@link
 *       RefundAllocation} rows.
 *   <li>{@code paymentId} — overpayments / unallocated excess never billed for. Comes straight off
 *       the payment's {@code unallocated_amount}; no RefundAllocation rows.
 * </ul>
 *
 * <p>Lifecycle PENDING → EXECUTED, with a CANCELLED branch off PENDING. The DEBIT transaction is
 * created and linked at {@link #execute}. See {@code sys-analysis/outbound/refund.md}.
 */
public final class Refund {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final UUID customerId;
  private final UUID creditNoteId;
  private final UUID paymentId;
  private final BigDecimal amount;
  private final String currency;
  private final PaymentProvider method;
  private final OffsetDateTime createdAt;

  private RefundStatus status;
  private UUID paymentTransactionId;
  private OffsetDateTime executedAt;
  private OffsetDateTime cancelledAt;
  private String cancelledReason;
  private String notes;
  private OffsetDateTime updatedAt;

  /**
   * Build a PENDING refund authorized by exactly one source. {@code creditNoteId} and {@code
   * paymentId} are mutually exclusive — exactly one must be non-null.
   */
  public static Refund createPending(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID creditNoteId,
      UUID paymentId,
      BigDecimal amount,
      String currency,
      PaymentProvider method,
      String notes,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(method, "method required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(now, "now required");
    Objects.requireNonNull(amount, "amount required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be > 0");
    }
    if ((creditNoteId == null) == (paymentId == null)) {
      throw new IllegalArgumentException("exactly one of creditNoteId / paymentId must be set");
    }
    return new Refund(
        id,
        orgId,
        customerId,
        creditNoteId,
        paymentId,
        amount.setScale(MONEY_SCALE, MONEY_ROUNDING),
        currency,
        method,
        now,
        RefundStatus.PENDING,
        null,
        null,
        null,
        null,
        notes,
        now);
  }

  /** Reconstitute from a persisted row — trusts DB invariants, skips creation-time validation. */
  public static Refund rehydrate(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID creditNoteId,
      UUID paymentId,
      BigDecimal amount,
      String currency,
      RefundStatus status,
      UUID paymentTransactionId,
      PaymentProvider method,
      OffsetDateTime executedAt,
      OffsetDateTime cancelledAt,
      String cancelledReason,
      String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    return new Refund(
        id,
        orgId,
        customerId,
        creditNoteId,
        paymentId,
        amount,
        currency,
        method,
        createdAt,
        status,
        paymentTransactionId,
        executedAt,
        cancelledAt,
        cancelledReason,
        notes,
        updatedAt);
  }

  private Refund(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID creditNoteId,
      UUID paymentId,
      BigDecimal amount,
      String currency,
      PaymentProvider method,
      OffsetDateTime createdAt,
      RefundStatus status,
      UUID paymentTransactionId,
      OffsetDateTime executedAt,
      OffsetDateTime cancelledAt,
      String cancelledReason,
      String notes,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.customerId = customerId;
    this.creditNoteId = creditNoteId;
    this.paymentId = paymentId;
    this.amount = amount;
    this.currency = currency;
    this.method = method;
    this.createdAt = createdAt;
    this.status = status;
    this.paymentTransactionId = paymentTransactionId;
    this.executedAt = executedAt;
    this.cancelledAt = cancelledAt;
    this.cancelledReason = cancelledReason;
    this.notes = notes;
    this.updatedAt = updatedAt;
  }

  /** True when this refund is authorized by a CreditNote (vs directly from a Payment). */
  public boolean isCreditNoteBacked() {
    return creditNoteId != null;
  }

  /**
   * Link the executing DEBIT {@link PaymentTransaction} and move PENDING → EXECUTED. The caller has
   * already created the transaction and moved the source aggregates in the same transaction.
   */
  public void execute(UUID paymentTransactionId, OffsetDateTime now) {
    if (this.status != RefundStatus.PENDING) {
      throw new InvalidOrderTransitionException(
          "cannot execute refund " + id + " in status " + status + "; expected PENDING");
    }
    Objects.requireNonNull(paymentTransactionId, "paymentTransactionId required");
    Objects.requireNonNull(now, "now required");
    this.paymentTransactionId = paymentTransactionId;
    this.status = RefundStatus.EXECUTED;
    this.executedAt = now;
    this.updatedAt = now;
  }

  /** PENDING → CANCELLED. The authorizing CreditNote (if any) is left ISSUED. */
  public void cancel(String reason, OffsetDateTime now) {
    if (this.status != RefundStatus.PENDING) {
      throw new InvalidOrderTransitionException(
          "cannot cancel refund " + id + " in status " + status + "; expected PENDING");
    }
    Objects.requireNonNull(now, "now required");
    this.status = RefundStatus.CANCELLED;
    this.cancelledReason = reason;
    this.cancelledAt = now;
    this.updatedAt = now;
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

  public UUID getCreditNoteId() {
    return creditNoteId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public RefundStatus getStatus() {
    return status;
  }

  public UUID getPaymentTransactionId() {
    return paymentTransactionId;
  }

  public PaymentProvider getMethod() {
    return method;
  }

  public OffsetDateTime getExecutedAt() {
    return executedAt;
  }

  public OffsetDateTime getCancelledAt() {
    return cancelledAt;
  }

  public String getCancelledReason() {
    return cancelledReason;
  }

  public String getNotes() {
    return notes;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public String toString() {
    return "Refund{id="
        + id
        + ", status="
        + status
        + ", amount="
        + amount
        + ", source="
        + (isCreditNoteBacked() ? "creditNote=" + creditNoteId : "payment=" + paymentId)
        + "}";
  }
}
