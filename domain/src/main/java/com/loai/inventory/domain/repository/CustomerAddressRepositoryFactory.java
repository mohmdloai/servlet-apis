package com.loai.inventory.domain.repository;

/** Creates a CustomerAddressRepository bound to a specific execution context: transactional ctx */
public interface CustomerAddressRepositoryFactory {
  CustomerAddressRepository create(Object ctx);
}
