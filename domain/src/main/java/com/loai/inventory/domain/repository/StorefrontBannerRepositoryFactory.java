package com.loai.inventory.domain.repository;

/**
 * Creates a {@link StorefrontBannerRepository} bound to a specific execution context (a
 * DSLContext).
 */
public interface StorefrontBannerRepositoryFactory {
  StorefrontBannerRepository create(Object ctx);
}
