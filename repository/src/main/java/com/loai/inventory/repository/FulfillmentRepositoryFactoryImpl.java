package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.FulfillmentRepository;
import com.loai.inventory.domain.repository.FulfillmentRepositoryFactory;
import org.jooq.DSLContext;

public final class FulfillmentRepositoryFactoryImpl implements FulfillmentRepositoryFactory {
  @Override
  public FulfillmentRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new FulfillmentRepositoryImpl(dsl);
  }
}
