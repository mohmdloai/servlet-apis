package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.ProductRepositoryFactory;
import org.jooq.DSLContext;

public final class ProductRepositoryFactoryImpl implements ProductRepositoryFactory {
  @Override
  public ProductRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new ProductRepositoryImpl(dsl);
  }
}
