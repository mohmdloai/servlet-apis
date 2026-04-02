package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import org.jooq.DSLContext;

/*
The service layer will inject InventoryRepositoryFactory and call factory.create(txCtx) inside transactionResult() so the
repository binds to the transactional DSLContext — not the root connection pool.
Service → InventoryRepositoryFactory.create(txCtx) → InventoryRepositoryImpl */
public final class InventoryRepositoryFactoryImpl implements InventoryRepositoryFactory {
  @Override
  public InventoryRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new InventoryRepositoryImpl(dsl);
  }
}
