package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.SupportTicketRepository;
import com.loai.inventory.domain.repository.SupportTicketRepositoryFactory;
import org.jooq.DSLContext;

public final class SupportTicketRepositoryFactoryImpl implements SupportTicketRepositoryFactory {
  @Override
  public SupportTicketRepository create(Object ctx) {
    return new SupportTicketRepositoryImpl((DSLContext) ctx);
  }
}
