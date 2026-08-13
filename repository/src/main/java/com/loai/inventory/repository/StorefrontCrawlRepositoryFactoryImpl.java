package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.StorefrontCrawlRepository;
import com.loai.inventory.domain.repository.StorefrontCrawlRepositoryFactory;
import org.jooq.DSLContext;

public final class StorefrontCrawlRepositoryFactoryImpl
    implements StorefrontCrawlRepositoryFactory {
  @Override
  public StorefrontCrawlRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new StorefrontCrawlRepositoryImpl(dsl);
  }
}
