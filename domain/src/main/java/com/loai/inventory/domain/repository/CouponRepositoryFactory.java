package com.loai.inventory.domain.repository;

/** Creates a CouponRepository bound to a specific execution context: transactional ctx. */
public interface CouponRepositoryFactory {
  CouponRepository create(Object ctx);
}
