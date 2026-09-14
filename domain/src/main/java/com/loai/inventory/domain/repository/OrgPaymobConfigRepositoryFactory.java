package com.loai.inventory.domain.repository;

/** Creates an OrgPaymobConfigRepository bound to a specific execution context: transactional ctx */
public interface OrgPaymobConfigRepositoryFactory {
  OrgPaymobConfigRepository create(Object ctx);
}
