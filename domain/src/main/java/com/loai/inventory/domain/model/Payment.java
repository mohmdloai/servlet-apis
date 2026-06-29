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
  private BigDecimal refundedAmount;
  private PaymentStatus status;
  private String notes;
  private OffsetDateTime disputedAt;
  private String disputeReason;
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
        BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING),
        PaymentStatus.RECEIVED,
        null,
        null,
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
      BigDecimal refundedAmount,
      PaymentStatus status,
      String notes,
      OffsetDateTime disputedAt,
      String disputeReason,
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
        refundedAmount,
        status,
        notes,
        disputedAt,
        disputeReason,
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
      BigDecimal refundedAmount,
      PaymentStatus status,
      String notes,
      OffsetDateTime disputedAt,
      String disputeReason,
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
    this.refundedAmount = refundedAmount;
    this.status = status;
    this.notes = notes;
    this.disputedAt = disputedAt;
    this.disputeReason = disputeReason;
    this.updatedAt = updatedAt;
  }

  /**
   * Apply {@code amount} of this payment to an invoice. Decrements {@code unallocatedAmount} and
   * advances {@code status}: RECEIVED → PARTIALLY_ALLOCATED while a remainder is left, → ALLOCATED
   * once fully consumed. Rejects over-allocation and payments whose money is gone (REFUNDED /
   * DISPUTED). Caller persists the resulting state and inserts the matching PaymentAllocation row
   * in the same transaction.
   */
  public void allocate(BigDecimal amount, OffsetDateTime now) {
    Objects.requireNonNull(amount, "amount required");
    Objects.requireNonNull(now, "now required");
    if (status == PaymentStatus.REFUNDED || status == PaymentStatus.DISPUTED) {
      throw new IllegalStateException("cannot allocate a " + status + " payment " + id);
    }
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("allocation amount must be > 0");
    }
    if (amount.compareTo(unallocatedAmount) > 0) {
      throw new IllegalStateException(
          "over-allocation of payment "
              + id
              + ": "
              + amount
              + " > unallocated "
              + unallocatedAmount);
    }
    this.unallocatedAmount =
        unallocatedAmount.subtract(amount).setScale(MONEY_SCALE, MONEY_ROUNDING);
    this.status =
        unallocatedAmount.signum() == 0
            ? PaymentStatus.ALLOCATED
            : PaymentStatus.PARTIALLY_ALLOCATED;
    this.updatedAt = now;
  }

  /**
   * Record {@code amount} of this payment being refunded, accruing the cached {@code
   * refundedAmount}. Two flavors, per {@code sys-analysis/outbound/refund.md}:
   *
   * <ul>
   *   <li>{@code fromAllocation=true} (CreditNote-backed): unwinds money previously allocated to an
   *       invoice. {@code unallocatedAmount} is untouched; status → PARTIALLY_REFUNDED (or REFUNDED
   *       once the whole payment is refunded). Bound: cannot refund more than is currently
   *       allocated ({@code amount − unallocated − refunded}).
   *   <li>{@code fromAllocation=false} (direct overpayment): takes money off {@code
   *       unallocatedAmount} that was never billed for. Status reflects the remaining allocation
   *       state (ALLOCATED / PARTIALLY_ALLOCATED / RECEIVED), or REFUNDED if the whole payment is
   *       gone. Bound: cannot refund more than {@code unallocatedAmount}.
   * </ul>
   *
   * Caller persists the resulting state and writes the matching RefundAllocation row (allocation
   * path) in the same transaction.
   *
   * <p>A DISPUTED payment is refundable only through the CreditNote ({@code fromAllocation}) path.
   * Because one CreditNote credits exactly one invoice, a disputed payment spanning several
   * invoices is fully refunded by several CreditNote-backed refunds. Each accrues {@code
   * refundedAmount} but the payment <b>stays frozen as DISPUTED</b> — it does not pass through
   * PARTIALLY_REFUNDED (state-machines.md F has no DISPUTED → PARTIALLY_REFUNDED edge) — until the
   * cumulative refunds cover the whole amount, at which point it becomes REFUNDED. The direct
   * (overpayment) path stays blocked while DISPUTED.
   */
  public void recordRefund(BigDecimal amount, boolean fromAllocation, OffsetDateTime now) {
    Objects.requireNonNull(amount, "amount required");
    Objects.requireNonNull(now, "now required");
    // A DISPUTED payment is frozen against ad-hoc moves, but the documented way to resolve a
    // dispute
    // is CreditNote + Refund (sys-analysis/outbound/payment.md §Disputed, state-machines.md F:
    // DISPUTED ──[admin refunds]──▶ REFUNDED). So the CreditNote-backed path may drain a DISPUTED
    // payment; the direct-from-Payment path stays blocked — disputed money is allocated to an
    // invoice, not sitting unallocated.
    if (status == PaymentStatus.DISPUTED && !fromAllocation) {
      throw new IllegalStateException("cannot direct-refund a DISPUTED payment " + id);
    }
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("refund amount must be > 0");
    }
    BigDecimal newRefunded = refundedAmount.add(amount).setScale(MONEY_SCALE, MONEY_ROUNDING);
    if (newRefunded.compareTo(this.amount) > 0) {
      throw new IllegalStateException(
          "over-refund of payment "
              + id
              + ": refunded "
              + newRefunded
              + " > amount "
              + this.amount);
    }
    if (fromAllocation) {
      BigDecimal allocated = this.amount.subtract(unallocatedAmount).subtract(refundedAmount);
      if (amount.compareTo(allocated) > 0) {
        throw new IllegalStateException(
            "over-refund of payment " + id + ": " + amount + " > allocated " + allocated);
      }
    } else {
      if (amount.compareTo(unallocatedAmount) > 0) {
        throw new IllegalStateException(
            "over-refund of payment " + id + ": " + amount + " > unallocated " + unallocatedAmount);
      }
      this.unallocatedAmount =
          unallocatedAmount.subtract(amount).setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
    this.refundedAmount = newRefunded;
    if (newRefunded.compareTo(this.amount) == 0) {
      this.status = PaymentStatus.REFUNDED;
    } else if (status == PaymentStatus.DISPUTED) {
      // Multi-invoice dispute resolution: the full refund arrives as several CreditNote-backed
      // refunds (one CreditNote per credited invoice). The payment stays frozen as DISPUTED — it
      // never enters PARTIALLY_REFUNDED (no such edge out of DISPUTED in state-machines.md F) — and
      // flips to REFUNDED above only once the cumulative refunds cover the whole amount. The direct
      // path is blocked above, so reaching here implies fromAllocation; unallocatedAmount is
      // untouched, so the freeze against new allocation / direct refund holds throughout.
      this.status = PaymentStatus.DISPUTED;
    } else if (fromAllocation) {
      this.status = PaymentStatus.PARTIALLY_REFUNDED;
    } else {
      // Direct-from-Payment refund: derive from what is still allocated to invoices. An orphan /
      // fully-unallocated payment (nothing allocated) reflects the refund itself
      // (PARTIALLY_REFUNDED), never a phantom PARTIALLY_ALLOCATED.
      BigDecimal allocated = this.amount.subtract(unallocatedAmount).subtract(newRefunded);
      if (allocated.signum() == 0) {
        this.status =
            newRefunded.signum() > 0 ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.RECEIVED;
      } else if (unallocatedAmount.signum() == 0) {
        this.status = PaymentStatus.ALLOCATED;
      } else {
        this.status = PaymentStatus.PARTIALLY_ALLOCATED;
      }
    }
    this.updatedAt = now;
  }

  /**
   * Flag a fully-allocated payment as DISPUTED — the customer claims it didn't happen, wasn't
   * authorized, or was wrong (sys-analysis/outbound/payment.md §Disputed; state-machines.md F). The
   * money is frozen: while DISPUTED a payment cannot be allocated to new invoices ({@link
   * #allocate}) nor refunded directly ({@link #recordRefund}); only an upheld decision ({@link
   * #uphold}) or a CreditNote-backed refund clears it. Only ALLOCATED payments enter dispute —
   * disputes happen after the money was reconciled and recognized.
   */
  public void dispute(String reason, OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (status != PaymentStatus.ALLOCATED) {
      throw new IllegalStateException(
          "only an ALLOCATED payment can be disputed; " + id + " is " + status);
    }
    this.status = PaymentStatus.DISPUTED;
    this.disputedAt = now;
    this.disputeReason = reason;
    this.updatedAt = now;
  }

  /**
   * Resolve a dispute in the org's favour: the payment was real and correct, so it returns to
   * ALLOCATED (state-machines.md F: DISPUTED ──[admin upholds]──▶ ALLOCATED). {@code disputedAt} /
   * {@code disputeReason} are retained as the audit trail of the resolved dispute.
   *
   * <p>A pre-dispute overpayment refund may leave {@code refundedAmount > 0} on an ALLOCATED (then
   * disputed) payment, so this method does not gate on {@code refundedAmount}. What it must
   * <i>not</i> allow is upholding after a <b>dispute-resolution</b> refund has begun (real money
   * already returned for the disputed money). That is gated in {@code PaymentDisputeService.uphold}
   * by the presence of a RefundAllocation against this payment — which can only have appeared
   * post-dispute, since any allocation-backed refund moves a payment off ALLOCATED before it could
   * be disputed.
   */
  public void uphold(OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (status != PaymentStatus.DISPUTED) {
      throw new IllegalStateException(
          "only a DISPUTED payment can be upheld; " + id + " is " + status);
    }
    this.status = PaymentStatus.ALLOCATED;
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

  public BigDecimal getRefundedAmount() {
    return refundedAmount;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public String getNotes() {
    return notes;
  }

  public OffsetDateTime getDisputedAt() {
    return disputedAt;
  }

  public String getDisputeReason() {
    return disputeReason;
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
