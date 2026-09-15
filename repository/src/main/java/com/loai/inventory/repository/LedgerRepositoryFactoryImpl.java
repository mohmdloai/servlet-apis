package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.LedgerRepository;
import com.loai.inventory.domain.repository.LedgerRepositoryFactory;
import org.jooq.DSLContext;

public final class LedgerRepositoryFactoryImpl implements LedgerRepositoryFactory {
  @Override
  public LedgerRepository create(Object ctx) {
    return new LedgerRepositoryImpl((DSLContext) ctx);
  }
}
