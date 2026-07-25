package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CollectionRepository;
import com.loai.inventory.domain.repository.CollectionRepositoryFactory;
import org.jooq.DSLContext;

public final class CollectionRepositoryFactoryImpl implements CollectionRepositoryFactory {
  @Override
  public CollectionRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new CollectionRepositoryImpl(dsl);
  }
}
