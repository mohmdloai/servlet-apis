package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.StorefrontBannerRepository;
import com.loai.inventory.domain.repository.StorefrontBannerRepositoryFactory;
import org.jooq.DSLContext;

public final class StorefrontBannerRepositoryFactoryImpl
    implements StorefrontBannerRepositoryFactory {
  @Override
  public StorefrontBannerRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new StorefrontBannerRepositoryImpl(dsl);
  }
}
