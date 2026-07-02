package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.NotificationRepository;
import com.loai.inventory.domain.repository.NotificationRepositoryFactory;
import org.jooq.DSLContext;

/** Binds a {@link NotificationRepository} to the given jOOQ {@link DSLContext}. */
public final class NotificationRepositoryFactoryImpl implements NotificationRepositoryFactory {
  @Override
  public NotificationRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new NotificationRepositoryImpl(dsl);
  }
}
