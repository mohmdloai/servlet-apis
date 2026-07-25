package com.loai.inventory.domain.repository;

/** Creates a ProductVariantRepository bound to a specific execution context: transactional ctx. */
public interface ProductVariantRepositoryFactory {
  ProductVariantRepository create(Object ctx);
}
