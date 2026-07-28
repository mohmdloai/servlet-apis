package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PlatformFunnelPath;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * The cross-org read behind {@code GET /api/admin/funnel} (slice 7 of the platform console, {@code
 * stories/platform_tenant_funnel.md}) — <strong>a fourth sibling</strong> in the enumerated set of
 * repositories the epic's architecture allows to omit {@code org_id} from a query (alongside {@link
 * PlatformStatsRepository}, {@link PlatformQueueRepository}, {@link PlatformSearchRepository}).
 * Same three rules as the others: platform-gated (reachable only through {@code
 * AuthzHelper.requirePlatformRead}), read-only, and — like {@code PlatformStatsRepository} —
 * <strong>counts only</strong>: no org row, no tenant name, no identifier crosses this boundary,
 * only aggregates.
 *
 * <p>Every method takes the same two filters: {@code from} (the cohort's lower bound on {@code
 * org.created_at}, {@code null} meaning no bound — the {@code cohort=all} case) and {@link
 * PlatformFunnelPath} (which acquisition-path split to apply, or {@link PlatformFunnelPath#ALL} for
 * none). Both filters describe the <em>same</em> set of orgs; {@link #cohortStats} and {@link
 * #stageCounts} are two aggregates over that one set, never two independently-filtered reads that
 * could drift apart.
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
}
