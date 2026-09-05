package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.PushSubscriptionRepository;
import com.loai.inventory.domain.repository.PushSubscriptionRepositoryFactory;
import org.jooq.DSLContext;

public final class PushSubscriptionRepositoryFactoryImpl
    implements PushSubscriptionRepositoryFactory {
  @Override
  public PushSubscriptionRepository create(Object ctx) {
    return new PushSubscriptionRepositoryImpl((DSLContext) ctx);
  }
}
