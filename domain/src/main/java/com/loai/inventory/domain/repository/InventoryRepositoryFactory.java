package com.loai.inventory.domain.repository;

/** Creates an InventoryRepository bound to a specific execution context: transactional ctx */
public interface InventoryRepositoryFactory {
  InventoryRepository create(Object ctx);
}
