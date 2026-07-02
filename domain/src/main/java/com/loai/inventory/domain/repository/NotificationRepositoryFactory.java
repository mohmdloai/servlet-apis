package com.loai.inventory.domain.repository;

/**
 * Binds a {@link NotificationRepository} to a transaction context (a jOOQ {@code DSLContext}, kept
 * as {@code Object} so the domain module stays jOOQ-free). Producers pass the caller's txn context;
 * feed reads pass the root context.
 */
public interface NotificationRepositoryFactory {
  NotificationRepository create(Object ctx);
}
