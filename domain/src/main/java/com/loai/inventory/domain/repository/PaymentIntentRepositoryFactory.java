package com.loai.inventory.domain.repository;

/** Creates a PaymentIntentRepository bound to a specific execution context: transactional ctx */
public interface PaymentIntentRepositoryFactory {
  PaymentIntentRepository create(Object ctx);
}
