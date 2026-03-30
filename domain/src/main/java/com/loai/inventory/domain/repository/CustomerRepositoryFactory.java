package com.loai.inventory.domain.repository;

/** Creates a CustomerRepository bound to a specific execution context: transactional ctx */
public interface CustomerRepositoryFactory {
  CustomerRepository create(Object ctx);
}
