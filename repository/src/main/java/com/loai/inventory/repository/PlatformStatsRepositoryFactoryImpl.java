package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PlatformStatsRepository;
import com.loai.inventory.domain.repository.PlatformStatsRepositoryFactory;
import org.jooq.DSLContext;

public final class PlatformStatsRepositoryFactoryImpl implements PlatformStatsRepositoryFactory {
  @Override
  public PlatformStatsRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new PlatformStatsRepositoryImpl(dsl);
  }
}
