package com.loai.inventory.domain.repository;

/**
 * Creates an {@link OrgMilestoneRepository} bound to a specific execution context: transactional
 * ctx
 */
public interface OrgMilestoneRepositoryFactory {
  OrgMilestoneRepository create(Object ctx);
}
