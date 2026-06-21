package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PaymentAllocationRepository;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import org.jooq.DSLContext;

public final class PaymentAllocationRepositoryFactoryImpl
    implements PaymentAllocationRepositoryFactory {
  @Override
  public PaymentAllocationRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new PaymentAllocationRepositoryImpl(dsl);
  }
}
