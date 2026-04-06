package com.loai.inventory.domain.repository;

/** Creates an InventoryLogRepository bound to a specific execution context: transactional ctx */
public interface InventoryLogRepositoryFactory {
  InventoryLogRepository create(Object ctx);
}
