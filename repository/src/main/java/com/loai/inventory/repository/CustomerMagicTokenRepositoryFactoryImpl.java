package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CustomerMagicTokenRepository;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepositoryFactory;
import org.jooq.DSLContext;

/** Binds a {@link CustomerMagicTokenRepository} to the given jOOQ {@link DSLContext}. */
public final class CustomerMagicTokenRepositoryFactoryImpl
    implements CustomerMagicTokenRepositoryFactory {
  @Override
  public CustomerMagicTokenRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new CustomerMagicTokenRepositoryImpl(dsl);
  }
}
