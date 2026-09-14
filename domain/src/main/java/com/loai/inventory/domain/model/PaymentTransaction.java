package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A real-world money event recorded at most once per {@code (provider, provider_ref)}. In the
 * manual InstaPay flow it starts as an {@code UNVERIFIED} customer claim; an admin then {@link
 * #verify}s it and the reconciliation pass stamps {@link #applyReconciliation a reconciliation
 * status}.
 *
 * <p>Verification machine (state-machines.md §D, {@code stories/payment_claim_verify.md}):
 *
 * <pre>
 *   [*] → UNVERIFIED            shopper files a claim (the order it names is stored)
 *   UNVERIFIED → VERIFIED       manager finds the transfer → reconciliation (E)
 *   UNVERIFIED → NOT_FOUND      manager cannot find it (reason kept; the shopper is told)
 *   NOT_FOUND → VERIFIED        found after all (a late transfer, a second look)
 *   NOT_FOUND → UNVERIFIED      the shopper re-files the same reference
 *   UNVERIFIED|NOT_FOUND → ABANDONED   a sibling claim settled the order, or the order left
 *                               PENDING_PAYMENT — reached by the system, never by a button
 * </pre>
 *
 * <p>{@code providerRef} is the idempotency key and is never edited: a wrong reference is a
 * NOT_FOUND on this row and a new row for the corrected one. Reconciliation is only meaningful
 * after VERIFIED.
 */
public class PaymentTransaction {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final PaymentProvider provider;
  private final String providerRef;
  private final PaymentDirection direction;
  private BigDecimal amount;
  private final String currency;
  private OffsetDateTime occurredAt;
  private final OffsetDateTime recordedAt;
  private final UUID claimedByCustomerId;
  private final UUID claimedSalesOrderId;
  private final String customerNote;
  private final String proofObjectKey;
  private final OffsetDateTime createdAt;

  private PaymentVerificationStatus verificationStatus;
  private UUID verifiedBy;
  private OffsetDateTime verifiedAt;
  private String verificationProof;
  private String notFoundReason;
  private String notFoundNote;
  private String rawPayload;
  private PaymentReconciliationStatus reconciliationStatus;
  private OffsetDateTime updatedAt;

  /** The cash shift this drawer event belongs to (stories/cash_shift.md); null off the counter. */
  private UUID cashShiftId;

  /**
   * A customer-claimed CREDIT transfer awaiting verification. Direction is fixed to {@code CREDIT}
   * (money in) and status to {@code UNVERIFIED}; reconciliation is null until verified. {@code
   * claimedSalesOrderId} is the order the <em>shopper</em> said this transfer pays — set only on a
   * row born as a shopper claim (null for an admin's free-form record and for in-store tenders,
   * where staff match at the counter and nobody "claims" anything).
   */
  public static PaymentTransaction createClaimed(
      UUID id,
      UUID orgId,
      PaymentProvider provider,
      String providerRef,
      BigDecimal amount,
      String currency,
      UUID claimedByCustomerId,
      UUID claimedSalesOrderId,
      String customerNote,
      String proofObjectKey,
      String verificationProof,
      OffsetDateTime occurredAt,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(provider, "provider required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(now, "now required");
    if (providerRef == null || providerRef.isBlank()) {
      throw new IllegalArgumentException("providerRef required");
    }
    Objects.requireNonNull(amount, "amount required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be > 0");
    }
    return new PaymentTransaction(
        id,
        orgId,
        provider,
        providerRef,
        PaymentDirection.CREDIT,
        amount.setScale(MONEY_SCALE, MONEY_ROUNDING),
        currency,
        occurredAt == null ? now : occurredAt,
        now,
        claimedByCustomerId,
        claimedSalesOrderId,
        customerNote,
        proofObjectKey,
        now,
        PaymentVerificationStatus.UNVERIFIED,
        null,
        null,
        verificationProof,
        null,
        null,
        null,
        null,
        now);
  }

  /**
   * The DEBIT (money-out) transaction that executes a {@link Refund}, created already VERIFIED —
   * the admin records the real-world reverse transfer they just performed, so there is no separate
   * verification step and no reconciliation (a refund is not matched against an order). Direction
   * is fixed to {@code DEBIT}.
   */
  public static PaymentTransaction createVerifiedDebit(
      UUID id,
      UUID orgId,
      PaymentProvider provider,
      String providerRef,
      BigDecimal amount,
      String currency,
      UUID verifiedBy,
      String verificationProof,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(provider, "provider required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(verifiedBy, "verifiedBy required");
    Objects.requireNonNull(now, "now required");
    if (providerRef == null || providerRef.isBlank()) {
      throw new IllegalArgumentException("providerRef required");
    }
    Objects.requireNonNull(amount, "amount required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be > 0");
    }
    return new PaymentTransaction(
        id,
        orgId,
        provider,
        providerRef,
        PaymentDirection.DEBIT,
        amount.setScale(MONEY_SCALE, MONEY_ROUNDING),
        currency,
        now,
        now,
        null,
        null,
        null,
        null,
        now,
        PaymentVerificationStatus.VERIFIED,
        verifiedBy,
        now,
        verificationProof,
        null,
        null,
        null,
        null,
        now);
  }

  /** Reconstitute from persistent state — trusts DB invariants, skips creation-time validation. */
  public static PaymentTransaction rehydrate(
      UUID id,
      UUID orgId,
      PaymentProvider provider,
      String providerRef,
      PaymentDirection direction,
      BigDecimal amount,
      String currency,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      UUID claimedByCustomerId,
      UUID claimedSalesOrderId,
      String customerNote,
      String proofObjectKey,
      OffsetDateTime createdAt,
      PaymentVerificationStatus verificationStatus,
      UUID verifiedBy,
      OffsetDateTime verifiedAt,
      String verificationProof,
      String notFoundReason,
      String notFoundNote,
      String rawPayload,
      PaymentReconciliationStatus reconciliationStatus,
      OffsetDateTime updatedAt,
      UUID cashShiftId) {
    PaymentTransaction txn =
        new PaymentTransaction(
            id,
            orgId,
            provider,
            providerRef,
            direction,
            amount,
            currency,
            occurredAt,
            recordedAt,
            claimedByCustomerId,
            claimedSalesOrderId,
            customerNote,
            proofObjectKey,
            createdAt,
            verificationStatus,
            verifiedBy,
            verifiedAt,
            verificationProof,
            notFoundReason,
            notFoundNote,
            rawPayload,
            reconciliationStatus,
            updatedAt);
    txn.cashShiftId = cashShiftId;
    return txn;
  }

  private PaymentTransaction(
      UUID id,
      UUID orgId,
      PaymentProvider provider,
      String providerRef,
      PaymentDirection direction,
      BigDecimal amount,
      String currency,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      UUID claimedByCustomerId,
      UUID claimedSalesOrderId,
      String customerNote,
      String proofObjectKey,
      OffsetDateTime createdAt,
      PaymentVerificationStatus verificationStatus,
      UUID verifiedBy,
      OffsetDateTime verifiedAt,
      String verificationProof,
      String notFoundReason,
      String notFoundNote,
      String rawPayload,
      PaymentReconciliationStatus reconciliationStatus,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.provider = provider;
    this.providerRef = providerRef;
    this.direction = direction;
    this.amount = amount;
    this.currency = currency;
    this.occurredAt = occurredAt;
    this.recordedAt = recordedAt;
    this.claimedByCustomerId = claimedByCustomerId;
    this.claimedSalesOrderId = claimedSalesOrderId;
    this.customerNote = customerNote;
    this.proofObjectKey = proofObjectKey;
    this.createdAt = createdAt;
    this.verificationStatus = verificationStatus;
    this.verifiedBy = verifiedBy;
    this.verifiedAt = verifiedAt;
    this.verificationProof = verificationProof;
    this.notFoundReason = notFoundReason;
    this.notFoundNote = notFoundNote;
    this.rawPayload = rawPayload;
    this.reconciliationStatus = reconciliationStatus;
    this.updatedAt = updatedAt;
  }

  /**
   * Admin confirms the real-world transfer happened. {@code UNVERIFIED → VERIFIED}, and also {@code
   * NOT_FOUND → VERIFIED}: "can't find it" is retryable by design — a transfer that was not in the
   * bank app at 09:00 may well be there at 11:00, and the manager taking a second look must not
   * need the shopper to re-file first. Clears any not-found reason: the row's current truth is that
   * the money is there.
   */
  public void verify(UUID verifiedBy, OffsetDateTime now) {
    Objects.requireNonNull(verifiedBy, "verifiedBy required");
    Objects.requireNonNull(now, "now required");
    requireOpenClaim("verify");
    this.verificationStatus = PaymentVerificationStatus.VERIFIED;
    this.verifiedBy = verifiedBy;
    this.verifiedAt = now;
    this.notFoundReason = null;
    this.notFoundNote = null;
    this.updatedAt = now;
  }

  /**
   * The gateway confirms the money moved ({@code stories/paymob_card_checkout.md}): a signed PSP
   * callback <em>is</em> the verification ({@code state-machines.md:225} — "VERIFIED happens
   * automatically on webhook receipt"), so {@code UNVERIFIED → VERIFIED} with {@code verifiedBy}
   * left <b>null</b>. The column is {@code UUID REFERENCES app_user(id)} and no user verified this;
   * a synthetic system user was considered and rejected — it would put a fake human in the audit
   * trail of every card payment, and {@code verified_by IS NULL AND provider = 'paymob_card'}
   * already reads unambiguously as "the gateway did it". {@code proof} names the mechanism.
   */
  public void verifyByGateway(String proof, OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    requireOpenClaim("verify");
    this.verificationStatus = PaymentVerificationStatus.VERIFIED;
    this.verifiedBy = null;
    this.verifiedAt = now;
    this.verificationProof = proof;
    this.notFoundReason = null;
    this.notFoundNote = null;
    this.updatedAt = now;
  }

  /**
   * "Can't find it": the manager searched the bank app for the reference and found nothing — {@code
   * UNVERIFIED → NOT_FOUND} with a reason ({@code NO_TRANSFER}, {@code DIFFERENT_ACCOUNT}, {@code
   * OTHER}) and an optional note for the shopper. Retryable by design: the shopper re-files ({@link
   * #reopen}) or the manager finds it after all ({@link #verify}). Only from UNVERIFIED — a claim
   * already NOT_FOUND has been answered; a second answer is a second not-found call the service
   * replays.
   */
  public void markNotFound(String reason, String note, OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason required");
    }
    if (verificationStatus != PaymentVerificationStatus.UNVERIFIED) {
      throw new IllegalStateException(
          "cannot mark transaction not found in status "
              + verificationStatus
              + "; expected UNVERIFIED");
    }
    this.verificationStatus = PaymentVerificationStatus.NOT_FOUND;
    this.notFoundReason = reason;
    this.notFoundNote = note;
    this.updatedAt = now;
  }

  /**
   * The system reason a claim carries when a manager recorded the real transfer under another
   * reference.
   */
  public static final String REASON_REFERENCE_DIFFERS = "REFERENCE_DIFFERS";

  /**
   * "Record a different transfer" from this claim ({@code stories/payment_claim_supersede.md}): the
   * screenshot showed the right transfer under a reference that differs from what the shopper
   * typed, so the manager records that reference and this claim is answered NOT_FOUND with the
   * system reason {@link #REASON_REFERENCE_DIFFERS} — from UNVERIFIED or from an earlier NOT_FOUND
   * (the note the manager left then is kept). The order is then paid through the new row, which
   * closes this one ABANDONED in the same transaction; the reason survives on the row.
   */
  public void supersede(OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    requireOpenClaim("supersede");
    this.verificationStatus = PaymentVerificationStatus.NOT_FOUND;
    this.notFoundReason = REASON_REFERENCE_DIFFERS;
    this.updatedAt = now;
  }

  /**
   * The shopper re-files the same reference after a not-found: {@code NOT_FOUND → UNVERIFIED}, the
   * manager's answer cleared. The reference is unchanged (it is the idempotency key) — this is
   * "please look again", not a new claim.
   */
  public void reopen(OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (verificationStatus != PaymentVerificationStatus.NOT_FOUND) {
      throw new IllegalStateException(
          "cannot reopen transaction in status " + verificationStatus + "; expected NOT_FOUND");
    }
    this.verificationStatus = PaymentVerificationStatus.UNVERIFIED;
    this.notFoundReason = null;
    this.notFoundNote = null;
    this.updatedAt = now;
  }

  /**
   * The bank's numbers, applied just before {@link #verify}: a claim freezes the order's
   * outstanding at filing time as its amount, but the manager confirms what the bank actually
   * shows. Each {@code null} keeps the claim's own value. Allowed only while the claim is still
   * open — a verified row's amount is a Payment's amount and never moves.
   */
  public void applyBankDetails(
      BigDecimal amount, OffsetDateTime occurredAt, String verificationProof, OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    requireOpenClaim("restate");
    if (amount != null) {
      if (amount.signum() <= 0) {
        throw new IllegalArgumentException("amount must be > 0");
      }
      this.amount = amount.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
    if (occurredAt != null) {
      this.occurredAt = occurredAt;
    }
    if (verificationProof != null) {
      this.verificationProof = verificationProof;
    }
    this.updatedAt = now;
  }

  /**
   * The system closes an open claim that can no longer be verified — a sibling claim settled the
   * order, or the order was cancelled / expired ({@code UNVERIFIED|NOT_FOUND → ABANDONED},
   * terminal). Never reached from a button.
   */
  public void abandon(OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    requireOpenClaim("abandon");
    this.verificationStatus = PaymentVerificationStatus.ABANDONED;
    this.updatedAt = now;
  }

  /**
   * Audit blob for the record path: when a manager records a free-form transfer against an order
   * that has open claims and explicitly acknowledges them, the acknowledged ids are written here so
   * the ledger shows the decision was deliberate. JSON text; persisted as {@code raw_payload}.
   */
  public void attachAudit(String rawPayloadJson) {
    this.rawPayload = rawPayloadJson;
  }

  /** Record the outcome of reconciling this verified transaction against an order. */
  public void applyReconciliation(PaymentReconciliationStatus status, OffsetDateTime now) {
    Objects.requireNonNull(status, "status required");
    Objects.requireNonNull(now, "now required");
    if (verificationStatus != PaymentVerificationStatus.VERIFIED) {
      throw new IllegalStateException(
          "cannot reconcile transaction in verification status " + verificationStatus);
    }
    this.reconciliationStatus = status;
    this.updatedAt = now;
  }

  /** True while the claim can still be verified: UNVERIFIED or NOT_FOUND. */
  public boolean isOpenClaim() {
    return verificationStatus == PaymentVerificationStatus.UNVERIFIED
        || verificationStatus == PaymentVerificationStatus.NOT_FOUND;
  }

  private void requireOpenClaim(String verb) {
    if (!isOpenClaim()) {
      throw new IllegalStateException(
          "cannot "
              + verb
              + " transaction in status "
              + verificationStatus
              + "; expected UNVERIFIED or NOT_FOUND");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public PaymentProvider getProvider() {
    return provider;
  }

  public String getProviderRef() {
    return providerRef;
  }

  public PaymentDirection getDirection() {
    return direction;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }

  public OffsetDateTime getRecordedAt() {
    return recordedAt;
  }

  public UUID getClaimedByCustomerId() {
    return claimedByCustomerId;
  }

  /** The order the shopper said this transfer pays (nullable — only a shopper claim sets it). */
  public UUID getClaimedSalesOrderId() {
    return claimedSalesOrderId;
  }

  public String getCustomerNote() {
    return customerNote;
  }

  /** The shopper's uploaded payment-proof object-storage key (nullable) — presign to view. */
  public String getProofObjectKey() {
    return proofObjectKey;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public PaymentVerificationStatus getVerificationStatus() {
    return verificationStatus;
  }

  public UUID getCashShiftId() {
    return cashShiftId;
  }

  /**
   * Attribute this drawer event to the org's open shift — set inside the sale's / return's own
   * transaction, before the row is written; never re-stamped later.
   */
  public void stampCashShift(UUID cashShiftId) {
    this.cashShiftId = cashShiftId;
  }

  public UUID getVerifiedBy() {
    return verifiedBy;
  }

  public OffsetDateTime getVerifiedAt() {
    return verifiedAt;
  }

  public String getVerificationProof() {
    return verificationProof;
  }

  /** Why the manager could not find the transfer — non-null only while NOT_FOUND. */
  public String getNotFoundReason() {
    return notFoundReason;
  }

  /** The manager's free note for the shopper beside the reason (nullable; NOT_FOUND only). */
  public String getNotFoundNote() {
    return notFoundNote;
  }

  /** Raw audit JSON (nullable) — see {@link #attachAudit}. */
  public String getRawPayload() {
    return rawPayload;
  }

  public PaymentReconciliationStatus getReconciliationStatus() {
    return reconciliationStatus;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public String toString() {
    return "PaymentTransaction{id="
        + id
        + ", provider="
        + provider
        + ", providerRef='"
        + providerRef
        + "', amount="
        + amount
        + ", verification="
        + verificationStatus
        + ", reconciliation="
        + reconciliationStatus
        + "}";
  }
}
