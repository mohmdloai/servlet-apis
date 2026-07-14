package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.CustomerAddressRepository;
import com.loai.inventory.domain.repository.CustomerAddressRepositoryFactory;
import org.jooq.DSLContext;

public final class CustomerAddressRepositoryFactoryImpl
    implements CustomerAddressRepositoryFactory {
  @Override
  public CustomerAddressRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new CustomerAddressRepositoryImpl(dsl);
  }
}
