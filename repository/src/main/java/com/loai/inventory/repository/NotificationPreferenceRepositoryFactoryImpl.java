package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.NotificationPreferenceRepository;
import com.loai.inventory.domain.repository.NotificationPreferenceRepositoryFactory;
import org.jooq.DSLContext;

/** Binds a {@link NotificationPreferenceRepository} to the given jOOQ {@link DSLContext}. */
public final class NotificationPreferenceRepositoryFactoryImpl
    implements NotificationPreferenceRepositoryFactory {
  @Override
  public NotificationPreferenceRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException(
          "Expected DSLContext, got: " + (ctx == null ? "null" : ctx.getClass().getName()));
    }
    return new NotificationPreferenceRepositoryImpl(dsl);
  }
}
