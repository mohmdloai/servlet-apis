package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import org.jooq.DSLContext;

public final class UserRepositoryFactoryImpl implements UserRepositoryFactory {
  @Override
  public UserRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new UserRepositoryImpl(dsl);
  }
}
