package com.loai.inventory.domain.repository;

/** Creates a {@link SalesInvoiceRepository} bound to a specific transactional context. */
public interface SalesInvoiceRepositoryFactory {
  SalesInvoiceRepository create(Object ctx);
}
