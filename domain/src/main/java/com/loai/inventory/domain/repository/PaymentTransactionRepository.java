package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import java.math.BigDecimal;
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
   *
   * <p>The ledger dimensions ({@code stories/transaction_filters.md}) only ever narrow and never
   * change the order: {@code q} is free text matched by fragment against the reference, the linked
   * order's number (the payment's order or the claimed one) and that order's / the claimant's
   * customer name and phone; {@code occurredFrom} / {@code occurredTo} are half-open {@code [from,
   * to)} on {@code occurred_at} — the bank's time, what the row shows; {@code minAmount} / {@code
   * maxAmount} are inclusive bounds on {@code amount}; {@code sort} is an explicit order, null =
   * the queue-vs-ledger default.
   */
  record ListFilter(
      PaymentVerificationStatus verificationStatus,
      PaymentReconciliationStatus reconciliationStatus,
      Boolean hasPayment,
      PaymentProvider provider,
      String providerRef,
      UUID claimedSalesOrderId,
      String q,
      OffsetDateTime occurredFrom,
      OffsetDateTime occurredTo,
      BigDecimal minAmount,
      BigDecimal maxAmount,
      Sort sort) {

    /** The explicit orders a caller may ask for; each breaks ties on {@code id}. */
    public enum Sort {
      /** {@code occurred_at DESC} — the most recent money first, whatever the view. */
      NEWEST,
      /** {@code occurred_at ASC} — the oldest money first, whatever the view. */
      OLDEST
    }

    /**
     * Normalizes {@code providerRef} and {@code q}: blank → null (no filter), otherwise trimmed.
     */
    public ListFilter {
      providerRef = providerRef == null || providerRef.isBlank() ? null : providerRef.trim();
      q = q == null || q.isBlank() ? null : q.trim();
    }

    /** The pre-ledger-filters six-predicate shape (the state view plus the exact lookups). */
    public ListFilter(
        PaymentVerificationStatus verificationStatus,
        PaymentReconciliationStatus reconciliationStatus,
        Boolean hasPayment,
        PaymentProvider provider,
        String providerRef,
        UUID claimedSalesOrderId) {
      this(
          verificationStatus,
          reconciliationStatus,
          hasPayment,
          provider,
          providerRef,
          claimedSalesOrderId,
          null,
          null,
          null,
          null,
          null,
          null);
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

    /** The unfiltered ledger. */
    public static ListFilter none() {
      return new ListFilter(null, null, null, null, null, null);
    }

    /** The same predicate with an explicit order. */
    public ListFilter sortedBy(Sort sort) {
      return new ListFilter(
          verificationStatus,
          reconciliationStatus,
          hasPayment,
          provider,
          providerRef,
          claimedSalesOrderId,
          q,
          occurredFrom,
          occurredTo,
          minAmount,
          maxAmount,
          sort);
    }

    /** The same predicate narrowed to one method. */
    public ListFilter withProvider(PaymentProvider provider) {
      return new ListFilter(
          verificationStatus,
          reconciliationStatus,
          hasPayment,
          provider,
          providerRef,
          claimedSalesOrderId,
          q,
          occurredFrom,
          occurredTo,
          minAmount,
          maxAmount,
          sort);
    }

    /** True when no predicate is set — the unfiltered ledger view. */
    public boolean isEmpty() {
      return !isQueue() && !hasNarrowing();
    }

    /**
     * True when the view is a <em>state</em> queue — a verification or reconciliation status, the
     * payment-exists exclusion, or the claims filed against one order. A queue reads oldest first
     * (the next thing to work). The narrowing dimensions ({@link #hasNarrowing}) never make a queue
     * of the ledger: a method, a window, a band or a search on "All transfers" keeps its
     * newest-first order, so a filter narrows the page without flipping it.
     */
    public boolean isQueue() {
      return verificationStatus != null
          || reconciliationStatus != null
          || hasPayment != null
          || claimedSalesOrderId != null;
    }

    /**
     * True when any ledger dimension narrows the rows: provider, reference, search, window, band.
     */
    public boolean hasNarrowing() {
      return provider != null
          || providerRef != null
          || q != null
          || occurredFrom != null
          || occurredTo != null
          || minAmount != null
          || maxAmount != null;
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

  /**
   * What the filtered set adds up to, in one pass over the same predicate as {@link #list}: the row
   * count the pager needs, and the money the ledger line owes its reader — {@code moneyIn} (Σ
   * {@code amount} over VERIFIED CREDITs: a claim resting UNVERIFIED is a row, not money) and
   * {@code moneyOut} (Σ {@code amount} over VERIFIED DEBITs — executed refunds). Both {@code 0.00}
   * for an empty set, never null ({@code stories/transaction_filters.md}).
   */
  record ListStats(long total, BigDecimal moneyIn, BigDecimal moneyOut) {
    public static ListStats empty() {
      return new ListStats(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }
  }

  ListStats stats(UUID orgId, ListFilter filter);

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

  /**
   * The latest shopper claim filed against {@code salesOrderId} in any state (newest {@code
   * recorded_at}), for the customer-facing order reads ({@code payment_claim} — "is the store still
   * looking, did they confirm, could they not find it"). Empty when no claim was ever filed.
   */
  Optional<PaymentTransaction> findLatestClaimByOrder(UUID salesOrderId);
}
