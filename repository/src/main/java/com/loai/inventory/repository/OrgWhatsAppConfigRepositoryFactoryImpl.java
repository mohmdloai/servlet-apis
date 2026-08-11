package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.OrgWhatsAppConfigRepository;
import com.loai.inventory.domain.repository.OrgWhatsAppConfigRepositoryFactory;
import org.jooq.DSLContext;

public final class OrgWhatsAppConfigRepositoryFactoryImpl
    implements OrgWhatsAppConfigRepositoryFactory {
  @Override
  public OrgWhatsAppConfigRepository create(Object ctx) {
    return new OrgWhatsAppConfigRepositoryImpl((DSLContext) ctx);
  }
}
