package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.OrgTimelineEntry;
import java.util.List;
import java.util.UUID;

/**
 * The per-tenant platform history behind {@code GET /api/admin/orgs/{orgId}/timeline} (slice 4,
 * {@code stories/platform_org_timeline.md}).
 *
 * <p><strong>This is not a fourth sibling of {@link PlatformStatsRepository} / {@link
 * PlatformQueueRepository} / {@link PlatformSearchRepository}, and it deliberately does not need to
 * be.</strong> Those three exist because their reads omit {@code org_id} entirely, which is the
 * property that costs the codebase its compiler-enforced tenancy and therefore has to be
 * enumerated. A timeline is scoped to one tenant by its own path segment, so every method here
 * takes {@code orgId} as its first parameter like any ordinary org-scoped read. The test for
 * whether a read needs a platform sibling is whether the query omits {@code org_id} — not whether
 * the caller happens to be an operator.
 *
 * <p><strong>The merge happens in SQL, ordered once.</strong> {@code platform_audit (org_id = ?)}
 * UNION ALL {@code impersonation_event (tier = 'ORG' AND scope_org_id = ?)} over a common
 * projection. Fetching a page from each source in Java and interleaving them would produce a page
 * that is missing rows — a truncating filter wearing a different hat — because neither source's
 * page boundary is the merged stream's.
 *
 * <p><strong>{@code tier = 'PLATFORM'} events are correctly absent.</strong> V41's {@code
 * ck_imp_scope} makes {@code (tier = 'ORG') = (scope_org_id IS NOT NULL)} an invariant, so a
 * platform-tier overlay carries no scope org and is not about any tenant.
 *
 * <p><strong>Nothing filters on org status</strong>, the same rule as the queues and search: a
 * suspended tenant has a history, and it is usually the one being asked about.
 */
public interface OrgTimelineRepository {

  /**
   * One page of the merged stream, <strong>newest-first always</strong> — {@code created_at DESC,
   * id DESC}, matching {@code PlatformAuditRepositoryImpl} exactly. This is a ledger, not a queue:
   * nobody reads a tenant's history oldest-first, they read the last thing that happened.
   *
   * <p>Actors are <em>not</em> resolved here; see {@link #actors}.
   */
  List<OrgTimelineEntry> find(UUID orgId, int offset, int limit);

  /** The true total across both ledgers, for the {@code PageResponse} envelope. */
  long count(UUID orgId);

  /**
   * Resolve a page's actor ids to {@code (email, display_name)} in <strong>one</strong> query — the
   * batch-load every worklist in this codebase uses, never a lookup per row. Ids with no surviving
   * {@code app_user} row are simply absent from the map, which is how a missing actor stays missing
   * instead of becoming an invention.
   */
  java.util.Map<UUID, String[]> actors(java.util.Collection<UUID> actorIds);
}
