package com.loai.inventory.domain.repository;

/** Creates an OrgRepository bound to a specific execution context: transactional ctx */
public interface OrgRepositoryFactory {
  OrgRepository create(Object ctx);
}
