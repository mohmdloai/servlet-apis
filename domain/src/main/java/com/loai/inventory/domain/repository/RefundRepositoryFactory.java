package com.loai.inventory.domain.repository;

/** Creates a {@link RefundRepository} bound to a specific transactional context. */
public interface RefundRepositoryFactory {
  RefundRepository create(Object ctx);
}
