package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.OrgMilestoneRepository;
import com.loai.inventory.domain.repository.OrgMilestoneRepositoryFactory;
import org.jooq.DSLContext;

public final class OrgMilestoneRepositoryFactoryImpl implements OrgMilestoneRepositoryFactory {
  @Override
  public OrgMilestoneRepository create(Object ctx) {
    if (!(ctx instanceof DSLContext dsl)) {
      throw new IllegalArgumentException("Expected DSLContext, got: " + ctx.getClass().getName());
    }
    return new OrgMilestoneRepositoryImpl(dsl);
  }
}
