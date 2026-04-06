package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import org.jooq.DSLContext;

public final class InventoryLogRepositoryFactoryImpl implements InventoryLogRepositoryFactory {
  @Override
  public InventoryLogRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new InventoryLogRepositoryImpl(dsl);
  }
}
