package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.RefundAllocationRepository;
import com.loai.inventory.domain.repository.RefundAllocationRepositoryFactory;
import org.jooq.DSLContext;

public final class RefundAllocationRepositoryFactoryImpl
    implements RefundAllocationRepositoryFactory {
  @Override
  public RefundAllocationRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new RefundAllocationRepositoryImpl(dsl);
  }
}
