package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.ListingCommentRepository;
import com.loai.inventory.domain.repository.ListingCommentRepositoryFactory;
import org.jooq.DSLContext;

public final class ListingCommentRepositoryFactoryImpl implements ListingCommentRepositoryFactory {
  @Override
  public ListingCommentRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new ListingCommentRepositoryImpl(dsl);
  }
}
