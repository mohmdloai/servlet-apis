package com.loai.inventory.domain.repository;

public interface PlatformTicketRepositoryFactory {
  PlatformTicketRepository create(Object ctx);
}
