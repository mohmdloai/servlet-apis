package com.loai.inventory.domain.repository;

/**
 * Creates a {@link PlatformQueueRepository} bound to a specific execution context (a DSLContext),
 * so the service keeps ownership of the transaction boundary like every other repository here.
 */
public interface PlatformQueueRepositoryFactory {
  PlatformQueueRepository create(Object ctx);
}
