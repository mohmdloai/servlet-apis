package com.loai.inventory.domain.repository;

/** Creates a CustomerWishlistRepository bound to a specific execution context: transactional ctx */
public interface CustomerWishlistRepositoryFactory {
  CustomerWishlistRepository create(Object ctx);
}
