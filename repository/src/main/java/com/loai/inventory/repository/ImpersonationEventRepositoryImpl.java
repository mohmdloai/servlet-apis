package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.IMPERSONATION_EVENT;

import com.loai.inventory.domain.model.ImpersonationEvent;
import com.loai.inventory.domain.repository.ImpersonationEventRepository;
import org.jooq.DSLContext;

public final class ImpersonationEventRepositoryImpl implements ImpersonationEventRepository {

  private final DSLContext dsl;

  public ImpersonationEventRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(ImpersonationEvent e) {
    dsl.insertInto(IMPERSONATION_EVENT)
        .set(IMPERSONATION_EVENT.IMPERSONATOR_ID, e.impersonatorId())
        .set(IMPERSONATION_EVENT.TARGET_ID, e.targetId())
        .set(IMPERSONATION_EVENT.TIER, e.tier().name())
        .set(IMPERSONATION_EVENT.SCOPE_ORG_ID, e.scopeOrgId())
        .set(IMPERSONATION_EVENT.EVENT, e.event().name())
        .set(IMPERSONATION_EVENT.REASON, e.reason())
        .set(IMPERSONATION_EVENT.SOURCE_IP, e.sourceIp())
        .set(IMPERSONATION_EVENT.USER_AGENT, e.userAgent())
        .execute();
  }
}
