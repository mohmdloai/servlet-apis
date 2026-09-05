package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CashShiftRepository;
import com.loai.inventory.domain.repository.CashShiftRepositoryFactory;
import org.jooq.DSLContext;

public final class CashShiftRepositoryFactoryImpl implements CashShiftRepositoryFactory {
  @Override
  public CashShiftRepository create(Object ctx) {
    return new CashShiftRepositoryImpl((DSLContext) ctx);
  }
}
