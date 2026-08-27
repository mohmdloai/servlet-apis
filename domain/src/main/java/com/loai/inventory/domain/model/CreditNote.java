package com.loai.inventory.domain.model;

import com.loai.inventory.common.exception.InvalidOrderTransitionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The negative-side mirror of a {@link SalesInvoice} — the accounting document that credits goods
 * or service previously billed, and the authorization for a CreditNote-backed {@link Refund}.
 * Always references the specific {@link SalesInvoice} it credits. A frozen snapshot once ISSUED.
 * See {@code sys-analysis/outbound/refund.md}.
 *
 * <p>Lifecycle DRAFT → ISSUED → SETTLED, with a VOID branch off ISSUED. The object is built DRAFT,
 * {@link #issue issued} (which assigns the gapless {@code CN-YYYY-NNNN} number) in the same
 * transaction, so DRAFT only ever exists in memory; the row is persisted already ISSUED. {@link
 * #settle} fires once executed refunds cover {@link #getTotal}.
 */
public final class CreditNote {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final UUID customerId;
  private final UUID salesInvoiceId;
  private final CreditNoteReason reason;
  private final String reasonNote;
  private final BigDecimal subtotal;
  private final BigDecimal taxTotal;
  private final BigDecimal total;
  private final String currency;
  private final OffsetDateTime createdAt;

  private CreditNoteStatus status;
  private String creditNoteNumber;
  private OffsetDateTime issuedAt;
  private OffsetDateTime updatedAt;

  /**
   * The credited lines' share of the invoice's discount (V89, {@code stories/counter_return.md}).
   * Lines carry the invoice line's GROSS unit price, so on a discounted sale this is what brings
   * {@code total} down to what the customer actually paid: {@code total = subtotal + taxTotal −
   * discountTotal}. Zero on every desk-issued note (the admin form types gross lines against a
   * gross cap of its own choosing).
   */
  private final BigDecimal discountTotal;

  /**
   * When a counter return put the goods back on the shelf; null when it did not (or never could).
   */
  private OffsetDateTime restockedAt;

  /** The counter return's {@code Idempotency-Key}; null for a desk-issued note. */
  private String idempotencyKey;

  /**
   * Build a DRAFT credit note with frozen totals. {@code creditNoteNumber} is set at {@link
   * #issue}.
   */
  public static CreditNote createDraft(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesInvoiceId,
      CreditNoteReason reason,
      String reasonNote,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      String currency,
      OffsetDateTime now) {
    return createDraft(
        id,
        orgId,
        customerId,
        salesInvoiceId,
        reason,
        reasonNote,
        subtotal,
        taxTotal,
        BigDecimal.ZERO,
        currency,
        now);
  }

  /**
   * A DRAFT note whose lines credit gross prices while the customer paid net ({@code discountTotal}
   * is their share of the invoice's discount — V89). {@code total = subtotal + tax − discount}, and
   * it must stay positive: a return worth nothing is not a credit note.
   */
  public static CreditNote createDraft(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesInvoiceId,
      CreditNoteReason reason,
      String reasonNote,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal discountTotal,
      String currency,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(salesInvoiceId, "salesInvoiceId required");
    Objects.requireNonNull(reason, "reason required");
    Objects.requireNonNull(subtotal, "subtotal required");
    Objects.requireNonNull(taxTotal, "taxTotal required");
    Objects.requireNonNull(discountTotal, "discountTotal required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(now, "now required");
    if (subtotal.signum() < 0 || taxTotal.signum() < 0 || discountTotal.signum() < 0) {
      throw new IllegalArgumentException("money fields must be >= 0");
    }
    BigDecimal sub = subtotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    BigDecimal tax = taxTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    BigDecimal disc = discountTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    if (disc.compareTo(sub) > 0) {
      throw new IllegalArgumentException("discount cannot exceed subtotal");
    }
    BigDecimal tot = sub.add(tax).subtract(disc);
    if (tot.signum() <= 0) {
      throw new IllegalArgumentException("total must be > 0");
    }
    return new CreditNote(
        id,
        orgId,
        customerId,
        salesInvoiceId,
        reason,
        reasonNote,
        sub,
        tax,
        tot,
        disc,
        currency,
        now,
        CreditNoteStatus.DRAFT,
        null,
        null,
        now);
  }

  /** Reconstitute from a persisted row — trusts DB invariants, skips creation-time validation. */
  public static CreditNote rehydrate(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesInvoiceId,
      CreditNoteReason reason,
      String reasonNote,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal total,
      String currency,
      String creditNoteNumber,
      CreditNoteStatus status,
      OffsetDateTime issuedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    return rehydrate(
        id,
        orgId,
        customerId,
        salesInvoiceId,
        reason,
        reasonNote,
        subtotal,
        taxTotal,
        total,
        BigDecimal.ZERO,
        currency,
        creditNoteNumber,
        status,
        issuedAt,
        createdAt,
        updatedAt,
        null,
        null);
  }

  /**
   * Reconstitute with the V89 columns — {@code discountTotal}, {@code restockedAt}, {@code
   * idempotencyKey}.
   */
  public static CreditNote rehydrate(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesInvoiceId,
      CreditNoteReason reason,
      String reasonNote,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal total,
      BigDecimal discountTotal,
      String currency,
      String creditNoteNumber,
      CreditNoteStatus status,
      OffsetDateTime issuedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      OffsetDateTime restockedAt,
      String idempotencyKey) {
    CreditNote n =
        new CreditNote(
            id,
            orgId,
            customerId,
            salesInvoiceId,
            reason,
            reasonNote,
            subtotal,
            taxTotal,
            total,
            discountTotal == null ? BigDecimal.ZERO : discountTotal,
            currency,
            createdAt,
            status,
            creditNoteNumber,
            issuedAt,
            updatedAt);
    n.restockedAt = restockedAt;
    n.idempotencyKey = idempotencyKey;
    return n;
  }

  private CreditNote(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesInvoiceId,
      CreditNoteReason reason,
      String reasonNote,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal total,
      BigDecimal discountTotal,
      String currency,
      OffsetDateTime createdAt,
      CreditNoteStatus status,
      String creditNoteNumber,
      OffsetDateTime issuedAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.customerId = customerId;
    this.salesInvoiceId = salesInvoiceId;
    this.reason = reason;
    this.reasonNote = reasonNote;
    this.subtotal = subtotal;
    this.taxTotal = taxTotal;
    this.total = total;
    this.discountTotal = discountTotal;
    this.currency = currency;
    this.createdAt = createdAt;
    this.status = status;
    this.creditNoteNumber = creditNoteNumber;
    this.issuedAt = issuedAt;
    this.updatedAt = updatedAt;
  }

  /**
   * Assign the gapless {@code creditNoteNumber} and move DRAFT → ISSUED, freezing the document. The
   * caller supplies the number claimed from the per-org per-year counter in the same transaction.
   */
  public void issue(String creditNoteNumber, OffsetDateTime now) {
    if (this.status != CreditNoteStatus.DRAFT) {
      throw new InvalidOrderTransitionException(
          "cannot issue credit note " + id + " in status " + status + "; expected DRAFT");
    }
    if (creditNoteNumber == null || creditNoteNumber.isBlank()) {
      throw new IllegalArgumentException("creditNoteNumber required");
    }
    Objects.requireNonNull(now, "now required");
    this.creditNoteNumber = creditNoteNumber;
    this.status = CreditNoteStatus.ISSUED;
    this.issuedAt = now;
    this.updatedAt = now;
  }

  /**
   * ISSUED → SETTLED once executed refunds cover the full {@link #getTotal}. Idempotent on SETTLED.
   */
  public void settle(OffsetDateTime now) {
    if (this.status == CreditNoteStatus.SETTLED) {
      return;
    }
    if (this.status != CreditNoteStatus.ISSUED) {
      throw new InvalidOrderTransitionException(
          "cannot settle credit note " + id + " in status " + status + "; expected ISSUED");
    }
    Objects.requireNonNull(now, "now required");
    this.status = CreditNoteStatus.SETTLED;
    this.updatedAt = now;
  }

  /**
   * ISSUED → VOID. Only legal while no refund has been EXECUTED against this note — the caller
   * enforces that precondition before calling.
   */
  public void voidNote(OffsetDateTime now) {
    if (this.status != CreditNoteStatus.ISSUED) {
      throw new InvalidOrderTransitionException(
          "cannot void credit note " + id + " in status " + status + "; expected ISSUED");
    }
    Objects.requireNonNull(now, "now required");
    this.status = CreditNoteStatus.VOID;
    this.updatedAt = now;
  }

  /**
   * The counter return put this note's goods back on the shelf (V89). Stamped once, in the same
   * transaction as the ledger rows and persisted through {@code
   * CreditNoteRepository.updateRestocked} — so a committed note either says so or never did.
   */
  public void markRestocked(OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (this.restockedAt != null) {
      throw new IllegalStateException("credit note " + id + " already restocked");
    }
    this.restockedAt = now;
  }

  /** The counter return's {@code Idempotency-Key}, set before insert (DRAFT only). */
  public void claimIdempotencyKey(String key) {
    if (this.status != CreditNoteStatus.DRAFT) {
      throw new InvalidOrderTransitionException(
          "cannot set idempotency key on credit note " + id + " in status " + status);
    }
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey required");
    }
    this.idempotencyKey = key;
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

  public UUID getSalesInvoiceId() {
    return salesInvoiceId;
  }

  public CreditNoteReason getReason() {
    return reason;
  }

  public String getReasonNote() {
    return reasonNote;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getTotal() {
    return total;
  }

  public BigDecimal getDiscountTotal() {
    return discountTotal;
  }

  public OffsetDateTime getRestockedAt() {
    return restockedAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getCurrency() {
    return currency;
  }

  public String getCreditNoteNumber() {
    return creditNoteNumber;
  }

  public CreditNoteStatus getStatus() {
    return status;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public String toString() {
    return "CreditNote{id="
        + id
        + ", number='"
        + creditNoteNumber
        + "', status="
        + status
        + ", total="
        + total
        + "}";
  }
}
