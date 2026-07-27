package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PlatformQueueCounts;
import com.loai.inventory.domain.model.PlatformTenantCounts;
import com.loai.inventory.domain.model.RecurringJobStats;
import java.util.List;

/**
 * The platform tier's cross-org rollups — <strong>the one place in this codebase where {@code
 * org_id} filtering is intentionally absent.</strong>
 *
 * <p>Everywhere else, every repository method takes {@code orgId} as its first parameter so tenancy
 * is compiler-enforced rather than remembered (CLAUDE.md §Key Patterns). The platform overview
 * needs figures that span every tenant, so it needs a read that deliberately omits that filter.
 * Confining the exception to this one named interface — rather than adding an "all orgs" overload
 * to {@code RefundRepository}, {@code PaymentRepository} and friends — is what keeps the rule true
 * everywhere else instead of quietly eroding it.
 *
 * <p>Three properties keep the exception safe, and they are the contract of this type:
 *
 * <ol>
 *   <li><strong>Gated on {@code SystemRole}.</strong> Its only caller is {@code
 *       PlatformOverviewService}, reached solely through {@code GET /api/admin/overview} behind
 *       {@code AuthzHelper.requirePlatformRead} (ADMIN or SUPPORT). No org-scoped route may call
 *       it.
 *   <li><strong>Counts only.</strong> Every method returns scalars. No tenant row, customer datum,
 *       email address or money amount crosses this boundary — there is no DTO here that
 *       <em>could</em> carry one, so a future change that wanted to leak data would have to change
 *       this interface, in a review, on purpose.
 *   <li><strong>Read-only.</strong> No mutation belongs here, ever.
 * </ol>
 *
 * <p>Do not add un-scoped reads to any other repository. If a second cross-org rollup is needed, it
 * belongs here, under the same three rules.
 */
public interface PlatformStatsRepository {

  /** Tenant census across every org. */
  PlatformTenantCounts tenantCounts();

  /** The five operational backlogs across every org. */
  PlatformQueueCounts queueCounts();

  /**
   * Outcome facts for the given recurring-job ids, read off {@code jobrunr_recurring_jobs} (the
   * registered set) and {@code jobrunr_jobs} (the outcomes). Only ids actually registered with
   * JobRunr come back, in the order requested — an id we register in code but that is missing from
   * the table is simply absent, never a fabricated row.
   */
  List<RecurringJobStats> recurringJobStats(List<String> jobIds);

  /** How many JobRunr background-job servers are currently registered. */
  long backgroundJobServerCount();
}
