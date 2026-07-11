package com.loai.inventory.domain.repository;

/** Creates a {@link NumberSequenceReconciliationRepository} bound to a transactional context. */
public interface NumberSequenceReconciliationRepositoryFactory {
  NumberSequenceReconciliationRepository create(Object ctx);
}
