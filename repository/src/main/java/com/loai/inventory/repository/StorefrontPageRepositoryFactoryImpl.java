package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.StorefrontPageRepository;
import com.loai.inventory.domain.repository.StorefrontPageRepositoryFactory;
import org.jooq.DSLContext;

public final class StorefrontPageRepositoryFactoryImpl implements StorefrontPageRepositoryFactory {
  @Override
  public StorefrontPageRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new StorefrontPageRepositoryImpl(dsl);
  }
}
