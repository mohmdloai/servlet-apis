package com.loai.inventory.domain.repository;

/** Creates a UserRepository bound to a specific execution context: transactional ctx */
public interface UserRepositoryFactory {
  UserRepository create(Object ctx);
}
