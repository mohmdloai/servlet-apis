package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PlatformQueueRepository;
import com.loai.inventory.domain.repository.PlatformQueueRepositoryFactory;
import org.jooq.DSLContext;

public final class PlatformQueueRepositoryFactoryImpl implements PlatformQueueRepositoryFactory {
  @Override
  public PlatformQueueRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new PlatformQueueRepositoryImpl(dsl);
  }
}
