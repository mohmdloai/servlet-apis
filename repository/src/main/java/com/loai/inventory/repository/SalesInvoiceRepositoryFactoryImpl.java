package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import org.jooq.DSLContext;

public final class SalesInvoiceRepositoryFactoryImpl implements SalesInvoiceRepositoryFactory {
  @Override
  public SalesInvoiceRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new SalesInvoiceRepositoryImpl(dsl);
  }
}
