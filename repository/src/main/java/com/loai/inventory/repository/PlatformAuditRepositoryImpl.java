package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;

import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.repository.PlatformAuditRepository;
import org.jooq.DSLContext;
import org.jooq.JSONB;

public final class PlatformAuditRepositoryImpl implements PlatformAuditRepository {

  private final DSLContext dsl;

  public PlatformAuditRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(PlatformAuditEvent e) {
    dsl.insertInto(PLATFORM_AUDIT)
        .set(PLATFORM_AUDIT.ACTOR_ID, e.actorId())
        .set(PLATFORM_AUDIT.ACTION, e.action())
        .set(PLATFORM_AUDIT.TARGET_TYPE, e.targetType())
        .set(PLATFORM_AUDIT.TARGET_ID, e.targetId())
        .set(PLATFORM_AUDIT.DETAIL, e.detailJson() == null ? null : JSONB.valueOf(e.detailJson()))
        .set(PLATFORM_AUDIT.SOURCE_IP, e.sourceIp())
        .set(PLATFORM_AUDIT.USER_AGENT, e.userAgent())
        .execute();
  }
}
