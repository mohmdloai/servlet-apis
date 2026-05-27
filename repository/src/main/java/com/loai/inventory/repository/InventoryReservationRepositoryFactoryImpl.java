package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import org.jooq.DSLContext;

public final class InventoryReservationRepositoryFactoryImpl
    implements InventoryReservationRepositoryFactory {
  @Override
  public InventoryReservationRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new InventoryReservationRepositoryImpl(dsl);
  }
}
