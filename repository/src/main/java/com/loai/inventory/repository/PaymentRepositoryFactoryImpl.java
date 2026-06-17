package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import org.jooq.DSLContext;

public final class PaymentRepositoryFactoryImpl implements PaymentRepositoryFactory {
  @Override
  public PaymentRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new PaymentRepositoryImpl(dsl);
  }
}
