package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentTransaction;
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
}
