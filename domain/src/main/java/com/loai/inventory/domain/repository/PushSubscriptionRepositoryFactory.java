package com.loai.inventory.domain.repository;

/**
 * Creates a PushSubscriptionRepository bound to a specific execution context: transactional ctx.
 */
public interface PushSubscriptionRepositoryFactory {
  PushSubscriptionRepository create(Object ctx);
}
