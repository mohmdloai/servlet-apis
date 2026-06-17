package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Payment}. Bound to a transactional {@code DSLContext} via {@link
 * PaymentRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface PaymentRepository {

  /** Insert a new payment row. */
  void insert(Payment payment);

  /**
   * Find the payment created from a given transaction ({@code payment.payment_transaction_id} is
   * {@code UNIQUE}). Used for idempotent replay of a verify call.
   */
  Optional<Payment> findByTransactionId(UUID orgId, UUID paymentTransactionId);
}
