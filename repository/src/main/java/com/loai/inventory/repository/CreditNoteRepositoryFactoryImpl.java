package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CreditNoteRepository;
import com.loai.inventory.domain.repository.CreditNoteRepositoryFactory;
import org.jooq.DSLContext;

public final class CreditNoteRepositoryFactoryImpl implements CreditNoteRepositoryFactory {
  @Override
  public CreditNoteRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new CreditNoteRepositoryImpl(dsl);
  }
}
