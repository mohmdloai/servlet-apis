package com.loai.inventory.domain.repository;

/** Creates a CollectionRepository bound to a specific execution context: transactional ctx. */
public interface CollectionRepositoryFactory {
  CollectionRepository create(Object ctx);
}
