package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
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
   * the orphan queue is built on ({@code transaction.md} §Operational queries).
   */
  record ListFilter(
      PaymentVerificationStatus verificationStatus,
      PaymentReconciliationStatus reconciliationStatus,
      Boolean hasPayment) {

    /** True when no predicate is set — the unfiltered ledger view. */
    public boolean isEmpty() {
      return verificationStatus == null && reconciliationStatus == null && hasPayment == null;
    }
  }

  /**
   * Page through the org's transactions. Filtered queries are queue views ordered oldest-first
   * ({@code occurred_at ASC, id ASC}); an empty filter is the ledger view ordered newest-first
   * ({@code recorded_at DESC, id DESC}). {@code id} tiebreaks keep pagination deterministic.
   */
  List<PaymentTransaction> list(UUID orgId, ListFilter filter, int offset, int limit);

  /** Count the transactions {@link #list} would return for the same filter. */
  long count(UUID orgId, ListFilter filter);

  /** Read a transaction by id without locking, scoped to the org. */
  Optional<PaymentTransaction> findById(UUID orgId, UUID id);
}
