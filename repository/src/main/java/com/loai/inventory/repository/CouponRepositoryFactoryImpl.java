package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CouponRepository;
import com.loai.inventory.domain.repository.CouponRepositoryFactory;
import org.jooq.DSLContext;

public final class CouponRepositoryFactoryImpl implements CouponRepositoryFactory {
  @Override
  public CouponRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new CouponRepositoryImpl(dsl);
  }
}
