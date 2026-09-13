package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PlatformTicketRepository;
import com.loai.inventory.domain.repository.PlatformTicketRepositoryFactory;
import org.jooq.DSLContext;

public final class PlatformTicketRepositoryFactoryImpl implements PlatformTicketRepositoryFactory {
  @Override
  public PlatformTicketRepository create(Object ctx) {
    return new PlatformTicketRepositoryImpl((DSLContext) ctx);
  }
}
