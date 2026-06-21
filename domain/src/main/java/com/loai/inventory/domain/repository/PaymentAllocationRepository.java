package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentAllocation;

/**
 * Persistence for {@link PaymentAllocation} rows. Bound to a transactional {@code DSLContext} via
 * {@link PaymentAllocationRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface PaymentAllocationRepository {

  /** Insert one immutable allocation row. */
  void insert(PaymentAllocation allocation);
}
