package com.loai.inventory.domain.repository;

public interface LedgerRepositoryFactory {
  LedgerRepository create(Object ctx);
}
