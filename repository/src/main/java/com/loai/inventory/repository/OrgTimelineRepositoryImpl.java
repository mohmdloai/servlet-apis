package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.IMPERSONATION_EVENT;
import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;

import com.loai.inventory.domain.model.OrgTimelineEntry;
import com.loai.inventory.domain.repository.OrgTimelineRepository;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record6;
import org.jooq.Select;
import org.jooq.SelectConditionStep;
import org.jooq.SelectOrderByStep;
import org.jooq.impl.DSL;

/**
 * The merged per-tenant timeline (slice 4). See {@link OrgTimelineRepository} for the contract.
 *
 * <p>The two ledgers are unioned and <strong>ordered once, in SQL</strong>. The alternative —
 * paging each source separately and interleaving in Java — silently drops rows, because neither
 * source's page boundary is the merged stream's.
 */
public final class OrgTimelineRepositoryImpl implements OrgTimelineRepository {

  /** V41's {@code ck_imp_scope} guarantees this tier is exactly the set carrying a scope org. */
  private static final String ORG_TIER = "ORG";

  private final DSLContext dsl;

  public OrgTimelineRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<OrgTimelineEntry> find(UUID orgId, int offset, int limit) {
    return merged(orgId)
        // Same tiebreak as PlatformAuditRepositoryImpl: created_at alone is not a total order, and
        // a page boundary landing between two rows sharing a timestamp would otherwise be able to
        // show one row twice and skip another.
        .orderBy(DSL.field(DSL.name("created_at")).desc(), DSL.field(DSL.name("id")).desc())
        .offset(offset)
        .limit(limit)
        .fetch(
            r ->
                new OrgTimelineEntry(
                    r.get(0, UUID.class),
                    r.get(1, OffsetDateTime.class),
                    OrgTimelineEntry.OrgTimelineSource.valueOf(r.get(2, String.class)),
                    r.get(3, String.class),
                    r.get(4, UUID.class),
                    null,
                    null,
                    detailOf(r.get(5, JSONB.class))));
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(merged(orgId));
  }

  @Override
  public Map<UUID, String[]> actors(Collection<UUID> actorIds) {
    if (actorIds == null || actorIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String[]> byId = new HashMap<>();
    dsl.select(APP_USER.ID, APP_USER.EMAIL, APP_USER.DISPLAY_NAME)
        .from(APP_USER)
        .where(APP_USER.ID.in(actorIds))
        .forEach(
            r ->
                byId.put(
                    r.get(APP_USER.ID),
                    new String[] {r.get(APP_USER.EMAIL), r.get(APP_USER.DISPLAY_NAME)}));
    return byId;
  }

  /**
   * {@code platform_audit (org_id = ?)} UNION ALL {@code impersonation_event (tier='ORG' AND
   * scope_org_id = ?)} over one projection. Nothing filters on org status — a suspended tenant has
   * a history, and it is usually the one being asked about.
   */
  private SelectOrderByStep<Record6<UUID, OffsetDateTime, String, String, UUID, JSONB>> merged(
      UUID orgId) {
    SelectConditionStep<Record6<UUID, OffsetDateTime, String, String, UUID, JSONB>> audits =
        dsl.select(
                PLATFORM_AUDIT.ID,
                PLATFORM_AUDIT.CREATED_AT,
                DSL.inline(OrgTimelineEntry.OrgTimelineSource.AUDIT.name()).as("source"),
                PLATFORM_AUDIT.ACTION.coerce(String.class).as("action"),
                PLATFORM_AUDIT.ACTOR_ID.as("actor_id"),
                PLATFORM_AUDIT.DETAIL.as("detail"))
            .from(PLATFORM_AUDIT)
            .where(PLATFORM_AUDIT.ORG_ID.eq(orgId));

    // The impersonated user is the payload, not the actor: the actor is whoever put the overlay on.
    Field<JSONB> impersonationDetail =
        DSL.field(
            "jsonb_build_object('target_id', {0}, 'reason', {1})",
            JSONB.class, IMPERSONATION_EVENT.TARGET_ID, IMPERSONATION_EVENT.REASON);

    Select<Record6<UUID, OffsetDateTime, String, String, UUID, JSONB>> overlays;
    overlays =
        dsl.select(
                IMPERSONATION_EVENT.ID,
                IMPERSONATION_EVENT.CREATED_AT,
                DSL.inline(OrgTimelineEntry.OrgTimelineSource.IMPERSONATION.name()).as("source"),
                // 'IMPERSONATION_START' / 'IMPERSONATION_STOP' — synthesized so both ledgers speak
                // one open-text vocabulary. Still open text: the client's fallback renders any verb
                // it does not know, so a third source could be added without a client change.
                DSL.concat(DSL.inline("IMPERSONATION_"), IMPERSONATION_EVENT.EVENT).as("action"),
                IMPERSONATION_EVENT.IMPERSONATOR_ID.as("actor_id"),
                impersonationDetail.as("detail"))
            .from(IMPERSONATION_EVENT)
            .where(
                IMPERSONATION_EVENT
                    .TIER
                    .eq(ORG_TIER)
                    .and(IMPERSONATION_EVENT.SCOPE_ORG_ID.eq(orgId)));

    return audits.unionAll(overlays);
  }

  private static String detailOf(JSONB detail) {
    return detail == null ? null : detail.data();
  }
}
