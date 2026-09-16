package com.loai.inventory.domain.repository;

/** Creates a SupplierRepository bound to a specific execution context: transactional ctx */
public interface SupplierRepositoryFactory {
  SupplierRepository create(Object ctx);
}
