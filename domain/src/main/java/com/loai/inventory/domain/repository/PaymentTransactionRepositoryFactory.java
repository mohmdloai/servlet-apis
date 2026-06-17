package com.loai.inventory.domain.repository;

/** Creates a {@link PaymentTransactionRepository} bound to a specific transactional context. */
public interface PaymentTransactionRepositoryFactory {
  PaymentTransactionRepository create(Object ctx);
}
