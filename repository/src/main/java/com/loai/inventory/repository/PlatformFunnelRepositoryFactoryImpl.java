package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PlatformFunnelRepository;
import com.loai.inventory.domain.repository.PlatformFunnelRepositoryFactory;
import org.jooq.DSLContext;

public final class PlatformFunnelRepositoryFactoryImpl implements PlatformFunnelRepositoryFactory {
  @Override
  public PlatformFunnelRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new PlatformFunnelRepositoryImpl(dsl);
  }
}
