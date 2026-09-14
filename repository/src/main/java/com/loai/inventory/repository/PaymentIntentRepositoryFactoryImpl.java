package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PaymentIntentRepository;
import com.loai.inventory.domain.repository.PaymentIntentRepositoryFactory;
import org.jooq.DSLContext;

public final class PaymentIntentRepositoryFactoryImpl implements PaymentIntentRepositoryFactory {
  @Override
  public PaymentIntentRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new PaymentIntentRepositoryImpl(dsl);
  }
}
