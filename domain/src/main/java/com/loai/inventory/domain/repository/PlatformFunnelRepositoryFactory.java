package com.loai.inventory.domain.repository;

/** Creates a {@link PlatformFunnelRepository} bound to a specific execution context. */
public interface PlatformFunnelRepositoryFactory {
  PlatformFunnelRepository create(Object ctx);
}
