package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PlatformQueueKind;
import com.loai.inventory.domain.model.PlatformQueueRow;
import java.util.List;
import java.util.UUID;

/**
 * The cross-org queue reads behind {@code GET /api/admin/queues/{kind}} — the second read in this
 * codebase that deliberately omits the {@code org_id} filter, and a <strong>sibling</strong> of
 * {@link PlatformStatsRepository} rather than an addition to it.
 *
 * <p>That separation is the point. {@code PlatformStatsRepository}'s safety argument is that it
 * returns <em>counts only</em> — "there is no DTO here that <em>could</em> carry one, so a future
 * change that wanted to leak data would have to change this interface, in a review, on purpose."
 * Adding row methods beside its counters would spend exactly that guarantee for convenience. So
 * rows live here, under their own three rules:
 *
 * <ol>
 *   <li><strong>Platform-gated.</strong> Reached solely through {@code GET /api/admin/queues/*}
 *       behind {@code AuthzHelper.requirePlatformRead} (ADMIN or SUPPORT). No org-scoped route may
 *       call it.
 *   <li><strong>Read-only.</strong> No mutation belongs here, ever.
 *   <li><strong>Rows, against a declared per-kind field whitelist.</strong> The whitelist is {@link
 *       PlatformQueueRow} — a sealed hierarchy, so it is the compiler that holds the line, and
 *       {@code PlatformQueuesIT.rows_carryNoCustomerPii} that keeps it true as fields get added.
 * </ol>
 *
 * <p><strong>The predicates are not restated here.</strong> A queue's membership rule is defined
 * once, in {@code PlatformQueuePredicates}, and consumed by both this repository's row queries and
 * {@link PlatformStatsRepository#queueCounts()}. That is what makes a tile and its drill-down the
 * same number by construction rather than by a test that passes right up until someone edits one of
 * them.
 *
 * <p><strong>Nothing filters on org status.</strong> A suspended tenant's rows are listed and
 * counted like any other; see {@link com.loai.inventory.domain.model.PlatformQueueOrg}.
 */
public interface PlatformQueueRepository {

  /**
   * One page of {@code kind}, <strong>oldest-first, always</strong>. Every org worklist in this
   * codebase switches between queue order (filtered, oldest-first) and ledger order (unfiltered,
   * newest-first); there is no ledger mode here, because a newest-first list of every failed email
   * across every tenant is not a thing anyone works. All five kinds are queues by construction, so
   * the convention collapses to its queue half.
   *
   * @param orgId narrows to one tenant; {@code null} spans every tenant. An unknown id yields an
   *     empty page, not an error — it is a filter, not a lookup.
   */
  List<PlatformQueueRow> page(PlatformQueueKind kind, UUID orgId, int offset, int limit);

  /**
   * How many rows are in {@code kind} (under the same optional {@code orgId} narrowing). With no
   * filter this is the same figure the overview tile shows, because it runs the same predicate.
   */
  long count(PlatformQueueKind kind, UUID orgId);
}
