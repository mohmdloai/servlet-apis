package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.SupplierRepository;
import com.loai.inventory.domain.repository.SupplierRepositoryFactory;
import org.jooq.DSLContext;

public final class SupplierRepositoryFactoryImpl implements SupplierRepositoryFactory {
  @Override
  public SupplierRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new SupplierRepositoryImpl(dsl);
  }
}
