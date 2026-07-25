package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.ProductVariantRepository;
import com.loai.inventory.domain.repository.ProductVariantRepositoryFactory;
import org.jooq.DSLContext;

public final class ProductVariantRepositoryFactoryImpl implements ProductVariantRepositoryFactory {
  @Override
  public ProductVariantRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new ProductVariantRepositoryImpl(dsl);
  }
}
