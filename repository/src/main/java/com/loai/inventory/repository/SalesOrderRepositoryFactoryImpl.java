package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import org.jooq.DSLContext;

public final class SalesOrderRepositoryFactoryImpl implements SalesOrderRepositoryFactory {
  @Override
  public SalesOrderRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new SalesOrderRepositoryImpl(dsl);
  }
}
