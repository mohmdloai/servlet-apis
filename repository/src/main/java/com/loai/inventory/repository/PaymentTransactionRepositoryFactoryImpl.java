package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import org.jooq.DSLContext;

public final class PaymentTransactionRepositoryFactoryImpl
    implements PaymentTransactionRepositoryFactory {
  @Override
  public PaymentTransactionRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new PaymentTransactionRepositoryImpl(dsl);
  }
}
