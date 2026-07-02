package com.loai.inventory.domain.repository;

/**
 * Binds a {@link NotificationPreferenceRepository} to a transaction context (a jOOQ {@code
 * DSLContext}, kept as {@code Object} so the domain module stays jOOQ-free). Resolution passes the
 * producer's txn; the CRUD/unsubscribe paths pass the root context.
 */
public interface NotificationPreferenceRepositoryFactory {
  NotificationPreferenceRepository create(Object ctx);
}
