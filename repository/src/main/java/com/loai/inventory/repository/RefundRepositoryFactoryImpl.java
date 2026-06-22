package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.RefundRepository;
import com.loai.inventory.domain.repository.RefundRepositoryFactory;
import org.jooq.DSLContext;

public final class RefundRepositoryFactoryImpl implements RefundRepositoryFactory {
  @Override
  public RefundRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new RefundRepositoryImpl(dsl);
  }
}
