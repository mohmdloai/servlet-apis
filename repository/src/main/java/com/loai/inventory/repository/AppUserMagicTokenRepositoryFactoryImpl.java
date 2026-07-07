package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.AppUserMagicTokenRepository;
import com.loai.inventory.domain.repository.AppUserMagicTokenRepositoryFactory;
import org.jooq.DSLContext;

/** Binds an {@link AppUserMagicTokenRepository} to the given jOOQ {@link DSLContext}. */
public final class AppUserMagicTokenRepositoryFactoryImpl
    implements AppUserMagicTokenRepositoryFactory {
  @Override
  public AppUserMagicTokenRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new AppUserMagicTokenRepositoryImpl(dsl);
  }
}
