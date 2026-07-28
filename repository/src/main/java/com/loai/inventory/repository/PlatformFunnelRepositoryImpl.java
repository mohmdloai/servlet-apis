package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.ORG_MILESTONE;
import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;

import com.loai.inventory.domain.model.PlatformFunnelPath;
import com.loai.inventory.domain.repository.PlatformFunnelRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;

/**
 * The cross-org rollup behind {@code GET /api/admin/funnel}. See {@link PlatformFunnelRepository}
 * for the fourth-sibling contract (platform-gated, counts-only, read-only).
 *
 * <p>{@link #cohortStats} and {@link #stageCounts} apply the <strong>identical</strong> {@code
 * (from, path)} condition — built once by {@link #cohortCondition} — so the funnel's denominator
 * and its per-stage numerators can never drift onto two different sets of orgs.
 *
 * <p><strong>No {@code ix_org_milestone_stage_time} index.</strong> The story's schema names one on
 * {@code (milestone, reached_at)}; measured on {@code perfdb} (200 orgs / 1,000,000 orders — the
 * scale that decides these questions) the planner chose a Seq Scan over {@code org_milestone} in
 * every shape tested, before and after building the candidate index, because the table is capped at
 * six rows per org by construction and never grows large enough for the index to beat a full scan.
 * V77's header carries the full capture; {@code tools/seed/results/org_milestone_backfill_135.txt}
 * has the raw EXPLAIN output.
 */
public final class PlatformFunnelRepositoryImpl implements PlatformFunnelRepository {

  private final DSLContext dsl;

  public PlatformFunnelRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public CohortStats cohortStats(OffsetDateTime from, PlatformFunnelPath path) {
    Condition cond = cohortCondition(from, path);
    Record2<Integer, OffsetDateTime> row =
        dsl.select(DSL.count(), DSL.max(ORG.CREATED_AT)).from(ORG).where(cond).fetchOne();
    if (row == null) {
      return new CohortStats(0, Optional.empty());
    }
    return new CohortStats(row.value1().longValue(), Optional.ofNullable(row.value2()));
  }

  @Override
  public Map<String, Long> stageCounts(OffsetDateTime from, PlatformFunnelPath path) {
    Condition cond = cohortCondition(from, path);
    Map<String, Long> counts = new LinkedHashMap<>();
    dsl.select(ORG_MILESTONE.MILESTONE, DSL.countDistinct(ORG_MILESTONE.ORG_ID))
        .from(ORG)
        .join(ORG_MILESTONE)
        .on(ORG_MILESTONE.ORG_ID.eq(ORG.ID))
        .where(cond)
        .groupBy(ORG_MILESTONE.MILESTONE)
        .fetch()
        .forEach(r -> counts.put(r.value1(), r.value2().longValue()));
    return counts;
  }

  /**
   * The one place the cohort/path condition is built — every method above consumes it verbatim, so
   * the denominator ({@link #cohortStats}) and the numerators ({@link #stageCounts}) are the same
   * set of orgs by construction.
   *
   * <p>{@code path} is resolved by whether an {@code ORG_CREATE} {@code platform_audit} row exists
   * for the org — provisioning writes one ({@code PlatformOrgService.provision}), self-serve
   * registration writes none ({@code AccountService} never calls {@code PlatformAuditService}).
   * Direct on {@code org_id} since V76.
   */
  private Condition cohortCondition(OffsetDateTime from, PlatformFunnelPath path) {
    Condition cond = from == null ? DSL.trueCondition() : ORG.CREATED_AT.ge(from);
    Condition orgCreateAudited =
        DSL.exists(
            DSL.selectOne()
                .from(PLATFORM_AUDIT)
                .where(
                    PLATFORM_AUDIT.ORG_ID.eq(ORG.ID).and(PLATFORM_AUDIT.ACTION.eq("ORG_CREATE"))));
    return switch (path) {
      case ALL -> cond;
      case SELF_SERVE -> cond.and(orgCreateAudited.not());
      case PROVISIONED -> cond.and(orgCreateAudited);
    };
  }
}
