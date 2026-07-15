package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.ListingReviewRepository;
import com.loai.inventory.domain.repository.ListingReviewRepositoryFactory;
import org.jooq.DSLContext;

public final class ListingReviewRepositoryFactoryImpl implements ListingReviewRepositoryFactory {
  @Override
  public ListingReviewRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new ListingReviewRepositoryImpl(dsl);
  }
}
