package com.loai.inventory.domain.repository;

/** Creates a {@link PaymentAllocationRepository} bound to a specific transactional context. */
public interface PaymentAllocationRepositoryFactory {
  PaymentAllocationRepository create(Object ctx);
}
