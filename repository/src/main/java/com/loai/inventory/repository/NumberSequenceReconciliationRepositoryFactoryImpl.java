package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.NumberSequenceReconciliationRepository;
import com.loai.inventory.domain.repository.NumberSequenceReconciliationRepositoryFactory;
import org.jooq.DSLContext;

public final class NumberSequenceReconciliationRepositoryFactoryImpl
    implements NumberSequenceReconciliationRepositoryFactory {
  @Override
  public NumberSequenceReconciliationRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new NumberSequenceReconciliationRepositoryImpl(dsl);
  }
}
