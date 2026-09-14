package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.OrgPaymobConfigRepository;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepositoryFactory;
import org.jooq.DSLContext;

public final class OrgPaymobConfigRepositoryFactoryImpl
    implements OrgPaymobConfigRepositoryFactory {
  @Override
  public OrgPaymobConfigRepository create(Object ctx) {
    return new OrgPaymobConfigRepositoryImpl((DSLContext) ctx);
  }
}
