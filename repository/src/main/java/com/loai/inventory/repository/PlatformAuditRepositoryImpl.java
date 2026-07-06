package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;

import com.loai.inventory.domain.model.PlatformAuditEntry;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.repository.PlatformAuditRepository;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;

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

  @Override
  public List<PlatformAuditEntry> find(int offset, int limit, String targetType, UUID actorId) {
    return dsl.selectFrom(PLATFORM_AUDIT)
        .where(filter(targetType, actorId))
        .orderBy(PLATFORM_AUDIT.CREATED_AT.desc(), PLATFORM_AUDIT.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch(PlatformAuditRepositoryImpl::toEntry);
  }

  @Override
  public long count(String targetType, UUID actorId) {
    return dsl.fetchCount(dsl.selectFrom(PLATFORM_AUDIT).where(filter(targetType, actorId)));
  }

  private static Condition filter(String targetType, UUID actorId) {
    Condition c = DSL.noCondition();
    if (targetType != null) {
      c = c.and(PLATFORM_AUDIT.TARGET_TYPE.eq(targetType));
    }
    if (actorId != null) {
      c = c.and(PLATFORM_AUDIT.ACTOR_ID.eq(actorId));
    }
    return c;
  }

  private static PlatformAuditEntry toEntry(Record r) {
    JSONB detail = r.get(PLATFORM_AUDIT.DETAIL);
    return new PlatformAuditEntry(
        r.get(PLATFORM_AUDIT.ID),
        r.get(PLATFORM_AUDIT.ACTOR_ID),
        r.get(PLATFORM_AUDIT.ACTION),
        r.get(PLATFORM_AUDIT.TARGET_TYPE),
        r.get(PLATFORM_AUDIT.TARGET_ID),
        detail == null ? null : detail.data(),
        r.get(PLATFORM_AUDIT.SOURCE_IP),
        r.get(PLATFORM_AUDIT.USER_AGENT),
        r.get(PLATFORM_AUDIT.CREATED_AT));
  }
}
