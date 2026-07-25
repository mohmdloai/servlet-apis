package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CustomerWishlistRepository;
import com.loai.inventory.domain.repository.CustomerWishlistRepositoryFactory;
import org.jooq.DSLContext;

public final class CustomerWishlistRepositoryFactoryImpl
    implements CustomerWishlistRepositoryFactory {
  @Override
  public CustomerWishlistRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new CustomerWishlistRepositoryImpl(dsl);
  }
}
