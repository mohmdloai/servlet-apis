package com.loai.inventory.domain.repository;

/** Creates a {@link PaymentRepository} bound to a specific transactional context. */
public interface PaymentRepositoryFactory {
  PaymentRepository create(Object ctx);
}
