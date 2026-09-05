package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CashMovementRepository;
import com.loai.inventory.domain.repository.CashMovementRepositoryFactory;
import org.jooq.DSLContext;

public final class CashMovementRepositoryFactoryImpl implements CashMovementRepositoryFactory {
  @Override
  public CashMovementRepository create(Object ctx) {
    return new CashMovementRepositoryImpl((DSLContext) ctx);
  }
}
