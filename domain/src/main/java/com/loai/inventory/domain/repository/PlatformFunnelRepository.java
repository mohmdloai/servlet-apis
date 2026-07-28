package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PlatformFunnelPath;
import com.loai.inventory.domain.model.PlatformGrowthBucket;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cross-org read behind {@code GET /api/admin/funnel} (slice 7 of the platform console, {@code
 * stories/platform_tenant_funnel.md}) <strong>and</strong> {@code GET /api/admin/growth} (slice 8,
 * {@code stories/platform_growth_series.md}) — <strong>a fourth sibling</strong> in the enumerated
 * set of repositories the epic's architecture allows to omit {@code org_id} from a query (alongside
 * {@link PlatformStatsRepository}, {@link PlatformQueueRepository}, {@link
 * PlatformSearchRepository}). Same three rules as the others: platform-gated (reachable only
 * through {@code AuthzHelper.requirePlatformRead}), read-only, and — like {@code
 * PlatformStatsRepository} — <strong>counts only</strong>: no org row, no tenant name, no
 * identifier crosses this boundary, only aggregates.
 *
 * <p><strong>One type serves both surfaces, deliberately — not a fifth sibling.</strong> The growth
 * series is the same table, the same counts-only contract, the same gate, and — decisively — the
 * same acquisition-path predicate: {@code cohortCondition} in the implementation is the one
 * definition of the self-serve/provisioned split, consumed by the funnel reads and {@link
 * #eventCounts} alike. Splitting the series into its own type would write that predicate twice,
 * which is the exact defect (one rule, two definitions) this epic keeps finding.
 *
 * <p>The funnel methods ({@link #cohortStats}, {@link #stageCounts}) take {@code from} as a
 * <em>cohort</em> bound on {@code org.created_at} — {@code null} meaning no bound, the {@code
 * cohort=all} case — and both describe the <em>same</em> set of orgs: two aggregates over one set,
 * never two independently-filtered reads that could drift apart. {@link #eventCounts} is the
 * <em>event</em> read: its {@code from} bounds {@code org_milestone.reached_at} instead, so an org
 * registered years ago whose first payment lands this week belongs in this week's point. The two
 * meanings of {@code from} are the cohort-vs-event distinction, not an inconsistency.
 */
public interface PlatformFunnelRepository {

  /** The cohort's size and the registration date of its newest (youngest) member. */
  record CohortStats(long size, Optional<OffsetDateTime> youngestCreatedAt) {}

  /** How many orgs in the cohort reached this stage, keyed by {@code org_milestone.milestone}. */
  CohortStats cohortStats(OffsetDateTime from, PlatformFunnelPath path);

  /**
   * Reached counts per milestone for the same cohort — only milestones with at least one org in
   * {@code org_milestone} are present; the caller fills in {@code 0} for the rest. Keyed by the raw
   * {@code milestone} text (not {@link com.loai.inventory.domain.model.PlatformFunnelStage}) so a
   * milestone the enum does not know still counts here and is simply dropped one layer up — the
   * open-text write / closed-enum read split {@code stories/platform_tenant_funnel.md} specifies.
   */
  Map<String, Long> stageCounts(OffsetDateTime from, PlatformFunnelPath path);

  /**
   * One milestone-events-per-bucket count: how many orgs on {@code path} reached {@code milestone}
   * in the {@code date_trunc(bucket, reached_at, 'UTC')} period starting at {@code period}. Keyed
   * by raw {@code milestone} text like {@link #stageCounts}, for the same open-text-write /
   * closed-enum-read reason.
   */
  record EventPoint(String milestone, OffsetDateTime period, long count) {}

  /**
   * Milestone events per time bucket — the growth series (slice 8). Unlike the funnel methods,
   * {@code from} bounds <strong>{@code reached_at}</strong>, not {@code org.created_at}: this is an
   * event read, so what matters is when the milestone happened, not when its org registered. {@code
   * null} means unbounded ({@code window=all}).
   *
   * <p>Rows are ordered {@code period ASC} within each milestone and are <strong>sparse</strong> —
   * a bucket with no events yields no row; absence means zero events, because {@code org_milestone}
   * is the complete record of the thing being counted (a deviation of meaning from the reports'
   * sparse contract, where an absent bucket merely wasn't summed — stated here so no caller
   * generalises either way). Zero-fill is the client's presentation concern.
   */
  List<EventPoint> eventCounts(
      OffsetDateTime from, PlatformGrowthBucket bucket, PlatformFunnelPath path);
}
