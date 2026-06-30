package com.loai.inventory.domain.repository;

/** Creates a CategoryRepository bound to a specific execution context: transactional ctx. */
public interface CategoryRepositoryFactory {
  CategoryRepository create(Object ctx);
}
