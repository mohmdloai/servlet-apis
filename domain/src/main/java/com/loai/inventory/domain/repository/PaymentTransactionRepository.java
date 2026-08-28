package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PaymentTransaction}. Bound to a transactional {@code DSLContext} via
 * {@link PaymentTransactionRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface PaymentTransactionRepository {

  /** A record-attempt outcome: the effective row plus whether this call inserted it. */
  record Recorded(PaymentTransaction transaction, boolean inserted) {}

  /**
   * Idempotently record a transaction: {@code INSERT … ON CONFLICT (provider, provider_ref) DO
   * NOTHING}. If the natural key already exists, the prior row is re-selected and returned with
   * {@code inserted=false} — the real-world money event is recorded at most once.
   */
  Recorded insertIfAbsent(PaymentTransaction transaction);

  /** Look up a transaction by its global natural key {@code (provider, provider_ref)}. */
  Optional<PaymentTransaction> findByProviderRef(PaymentProvider provider, String providerRef);

  /**
   * Load a transaction by id with a row write lock ({@code SELECT … FOR UPDATE}), scoped to the
   * org. Used by orphan resolution to serialize the ORPHAN → MATCHED flip against any concurrent
   * resolve of the same transaction.
   */
  Optional<PaymentTransaction> findByIdForUpdate(UUID orgId, UUID id);

  /** Persist verification + reconciliation columns for an existing transaction. */
  void update(PaymentTransaction transaction);

  /**
   * Optional list predicates, ANDed; a {@code null} field means "no filter on this column". {@code
   * hasPayment} filters on the existence of the 1:1 {@code payment} row bound to the transaction
   * ({@code payment.payment_transaction_id} UNIQUE) — {@code false} is the payment-exists exclusion
   * the orphan queue is built on ({@code transaction.md} §Operational queries). {@code provider} /
   * {@code providerRef} are literal column matches ({@code providerRef} case-sensitive — it is the
   * provider's identifier, not user prose) for the support lookup "did we record this reference?"
   * ({@code stories/lookup_transaction_by_reference.md}). {@code claimedSalesOrderId} narrows to
   * the claims filed against one order ({@code claimed_sales_order_id}) — the order page's
   * "customer says they paid" card ({@code stories/payment_claim_verify.md}); it composes with
   * {@code verificationStatus}.
   */
  record ListFilter(
      PaymentVerificationStatus verificationStatus,
      PaymentReconciliationStatus reconciliationStatus,
      Boolean hasPayment,
      PaymentProvider provider,
      String providerRef,
      UUID claimedSalesOrderId) {

    /** Normalizes {@code providerRef}: blank → null (no filter), otherwise trimmed. */
    public ListFilter {
      providerRef = providerRef == null || providerRef.isBlank() ? null : providerRef.trim();
    }

    /** The pre-claims five-predicate shape; no order filter. */
    public ListFilter(
        PaymentVerificationStatus verificationStatus,
        PaymentReconciliationStatus reconciliationStatus,
        Boolean hasPayment,
        PaymentProvider provider,
        String providerRef) {
      this(verificationStatus, reconciliationStatus, hasPayment, provider, providerRef, null);
    }

    /** True when no predicate is set — the unfiltered ledger view. */
    public boolean isEmpty() {
      return verificationStatus == null
          && reconciliationStatus == null
          && hasPayment == null
          && provider == null
          && providerRef == null
          && claimedSalesOrderId == null;
    }

    /**
     * True when the view is a claims queue ({@code verification_status} UNVERIFIED or NOT_FOUND):
     * these are ordered by the <em>claimed order's</em> clock, not by the transaction's own time.
     */
    public boolean isClaimsQueue() {
      return verificationStatus == PaymentVerificationStatus.UNVERIFIED
          || verificationStatus == PaymentVerificationStatus.NOT_FOUND;
    }
  }

  /**
   * Page through the org's transactions. Filtered queries are queue views ordered oldest-first
   * ({@code occurred_at ASC, id ASC}); an empty filter is the ledger view ordered newest-first
   * ({@code recorded_at DESC, id DESC}). A claims queue ({@link ListFilter#isClaimsQueue}) is the
   * exception: it is ordered by urgency — the claimed order's {@code expires_at ASC NULLS LAST},
   * then {@code recorded_at ASC} — because what the queue can lose is the order, not the row.
   * {@code id} tiebreaks keep pagination deterministic.
   */
  List<PaymentTransaction> list(UUID orgId, ListFilter filter, int offset, int limit);

  /** Count the transactions {@link #list} would return for the same filter. */
  long count(UUID orgId, ListFilter filter);

  /** Read a transaction by id without locking, scoped to the org. */
  Optional<PaymentTransaction> findById(UUID orgId, UUID id);

  /**
   * The open claims (UNVERIFIED or NOT_FOUND) filed against one order, oldest first — the record
   * path's pending-claim guard and the order page's claim card ({@code
   * stories/payment_claim_verify.md}). Runs in the caller's transaction; not locking.
   */
  List<PaymentTransaction> findOpenClaimsByOrder(UUID orgId, UUID salesOrderId);

  /**
   * Close every open claim on {@code salesOrderId} except {@code exceptTransactionId} (nullable) as
   * {@code ABANDONED}, stamping {@code updated_at = now}. One guarded UPDATE, so it is safe against
   * a concurrent verify of the same rows (a row that just became VERIFIED no longer matches).
   * Called when a sibling claim settles the order, and by expiry / cancellation. Returns the rows
   * closed.
   */
  int abandonOpenClaims(UUID salesOrderId, UUID exceptTransactionId, OffsetDateTime now);
}
