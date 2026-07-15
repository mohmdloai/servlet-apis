package com.loai.inventory.domain.repository;

/**
 * Creates a {@link StorefrontPageRepository} bound to a specific execution context (a DSLContext).
 */
public interface StorefrontPageRepositoryFactory {
  StorefrontPageRepository create(Object ctx);
}
