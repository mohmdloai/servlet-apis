package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import org.jooq.DSLContext;

public final class ProductListingRepositoryFactoryImpl implements ProductListingRepositoryFactory {
  @Override
  public ProductListingRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new ProductListingRepositoryImpl(dsl);
  }
}
