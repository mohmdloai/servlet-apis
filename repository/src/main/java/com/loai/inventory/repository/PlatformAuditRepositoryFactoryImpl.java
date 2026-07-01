package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PlatformAuditRepository;
import com.loai.inventory.domain.repository.PlatformAuditRepositoryFactory;
import org.jooq.DSLContext;

public final class PlatformAuditRepositoryFactoryImpl implements PlatformAuditRepositoryFactory {
  @Override
  public PlatformAuditRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new PlatformAuditRepositoryImpl(dsl);
  }
}
