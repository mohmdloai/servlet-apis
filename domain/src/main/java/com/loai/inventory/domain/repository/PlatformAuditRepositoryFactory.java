package com.loai.inventory.domain.repository;

/**
 * Creates a PlatformAuditRepository bound to a specific execution context (a transactional
 * DSLContext), so an audit row can be written inside the same transaction as the action it records.
 */
public interface PlatformAuditRepositoryFactory {
  PlatformAuditRepository create(Object ctx);
}
