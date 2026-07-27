package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PlatformSearchRepository;
import com.loai.inventory.domain.repository.PlatformSearchRepositoryFactory;
import org.jooq.DSLContext;

public final class PlatformSearchRepositoryFactoryImpl implements PlatformSearchRepositoryFactory {
  @Override
  public PlatformSearchRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new PlatformSearchRepositoryImpl(dsl);
  }
}
