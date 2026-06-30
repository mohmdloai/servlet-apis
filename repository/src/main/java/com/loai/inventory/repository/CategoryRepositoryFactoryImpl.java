package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import org.jooq.DSLContext;

public final class CategoryRepositoryFactoryImpl implements CategoryRepositoryFactory {
  @Override
  public CategoryRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new CategoryRepositoryImpl(dsl);
  }
}
