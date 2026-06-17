package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentTransaction;
import java.util.Optional;

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

  /** Persist verification + reconciliation columns for an existing transaction. */
  void update(PaymentTransaction transaction);
}
