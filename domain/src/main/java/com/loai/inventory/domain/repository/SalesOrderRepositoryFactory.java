package com.loai.inventory.domain.repository;

/** Creates a {@link SalesOrderRepository} bound to a specific transactional context. */
public interface SalesOrderRepositoryFactory {
  SalesOrderRepository create(Object ctx);
}
