package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ORG_MILESTONE;

import com.loai.inventory.domain.repository.OrgMilestoneRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Postgres/jOOQ implementation of {@code org_milestone} (slice 7, {@code
 * stories/platform_tenant_funnel.md}). The whole contract is one {@code ON CONFLICT DO NOTHING}
 * insert — see {@link OrgMilestoneRepository}'s Javadoc for why idempotence lives in the PRIMARY
 * KEY rather than in this class.
 */
public final class OrgMilestoneRepositoryImpl implements OrgMilestoneRepository {

  private final DSLContext dsl;

  public OrgMilestoneRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public boolean insertIfAbsent(UUID orgId, String milestone, OffsetDateTime reachedAt) {
    return dsl.insertInto(ORG_MILESTONE)
            .set(ORG_MILESTONE.ORG_ID, orgId)
            .set(ORG_MILESTONE.MILESTONE, milestone)
            .set(ORG_MILESTONE.REACHED_AT, reachedAt)
            .onConflictDoNothing()
            .execute()
        > 0;
  }
}
