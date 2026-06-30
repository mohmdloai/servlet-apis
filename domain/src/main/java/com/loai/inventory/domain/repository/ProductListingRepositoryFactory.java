package com.loai.inventory.domain.repository;

/** Creates a ProductListingRepository bound to a specific execution context: transactional ctx. */
public interface ProductListingRepositoryFactory {
  ProductListingRepository create(Object ctx);
}
