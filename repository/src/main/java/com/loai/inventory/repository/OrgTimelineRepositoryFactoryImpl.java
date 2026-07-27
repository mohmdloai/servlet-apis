package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.OrgTimelineRepository;
import com.loai.inventory.domain.repository.OrgTimelineRepositoryFactory;
import org.jooq.DSLContext;

public final class OrgTimelineRepositoryFactoryImpl implements OrgTimelineRepositoryFactory {
  @Override
  public OrgTimelineRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new OrgTimelineRepositoryImpl(dsl);
  }
}
