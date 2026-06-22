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
 * <p>State machines (see V22): verification {@code UNVERIFIED → VERIFIED}; reconciliation is only
 * meaningful after VERIFIED.
 */
public class PaymentTransaction {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final PaymentProvider provider;
  private final String providerRef;
  private final PaymentDirection direction;
  private final BigDecimal amount;
  private final String currency;
  private final OffsetDateTime occurredAt;
  private final OffsetDateTime recordedAt;
  private final UUID claimedByCustomerId;
  private final String customerNote;
  private final OffsetDateTime createdAt;

  private PaymentVerificationStatus verificationStatus;
  private UUID verifiedBy;
  private OffsetDateTime verifiedAt;
  private String verificationProof;
  private PaymentReconciliationStatus reconciliationStatus;
  private OffsetDateTime updatedAt;

  /**
   * A customer-claimed CREDIT transfer awaiting verification. Direction is fixed to {@code CREDIT}
   * (money in) and status to {@code UNVERIFIED}; reconciliation is null until verified.
   */
  public static PaymentTransaction createClaimed(
      UUID id,
      UUID orgId,
      PaymentProvider provider,
      String providerRef,
      BigDecimal amount,
      String currency,
      UUID claimedByCustomerId,
      String customerNote,
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
        customerNote,
        now,
        PaymentVerificationStatus.UNVERIFIED,
        null,
        null,
        verificationProof,
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
        now,
        PaymentVerificationStatus.VERIFIED,
        verifiedBy,
        now,
        verificationProof,
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
      String customerNote,
      OffsetDateTime createdAt,
      PaymentVerificationStatus verificationStatus,
      UUID verifiedBy,
      OffsetDateTime verifiedAt,
      String verificationProof,
      PaymentReconciliationStatus reconciliationStatus,
      OffsetDateTime updatedAt) {
    return new PaymentTransaction(
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
        customerNote,
        createdAt,
        verificationStatus,
        verifiedBy,
        verifiedAt,
        verificationProof,
        reconciliationStatus,
        updatedAt);
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
      String customerNote,
      OffsetDateTime createdAt,
      PaymentVerificationStatus verificationStatus,
      UUID verifiedBy,
      OffsetDateTime verifiedAt,
      String verificationProof,
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
    this.customerNote = customerNote;
    this.createdAt = createdAt;
    this.verificationStatus = verificationStatus;
    this.verifiedBy = verifiedBy;
    this.verifiedAt = verifiedAt;
    this.verificationProof = verificationProof;
    this.reconciliationStatus = reconciliationStatus;
    this.updatedAt = updatedAt;
  }

  /** Admin confirms the real-world transfer happened. {@code UNVERIFIED → VERIFIED}. */
  public void verify(UUID verifiedBy, OffsetDateTime now) {
    Objects.requireNonNull(verifiedBy, "verifiedBy required");
    Objects.requireNonNull(now, "now required");
    if (verificationStatus != PaymentVerificationStatus.UNVERIFIED) {
      throw new IllegalStateException(
          "cannot verify transaction in status " + verificationStatus + "; expected UNVERIFIED");
    }
    this.verificationStatus = PaymentVerificationStatus.VERIFIED;
    this.verifiedBy = verifiedBy;
    this.verifiedAt = now;
    this.updatedAt = now;
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

  public String getCustomerNote() {
    return customerNote;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public PaymentVerificationStatus getVerificationStatus() {
    return verificationStatus;
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
