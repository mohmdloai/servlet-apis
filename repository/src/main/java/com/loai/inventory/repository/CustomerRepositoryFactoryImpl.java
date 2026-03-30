package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import org.jooq.DSLContext;

public final class CustomerRepositoryFactoryImpl implements CustomerRepositoryFactory {
  @Override
  public CustomerRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new CustomerRepositoryImpl(dsl);
  }
}
