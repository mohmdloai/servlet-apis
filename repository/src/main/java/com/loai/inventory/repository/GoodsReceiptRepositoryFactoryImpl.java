package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.GoodsReceiptRepository;
import com.loai.inventory.domain.repository.GoodsReceiptRepositoryFactory;
import org.jooq.DSLContext;

public final class GoodsReceiptRepositoryFactoryImpl implements GoodsReceiptRepositoryFactory {
  @Override
  public GoodsReceiptRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new GoodsReceiptRepositoryImpl(dsl);
  }
}
