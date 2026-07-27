package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgTimelineEntry;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.OrgTimelineRepository;
import com.loai.inventory.domain.repository.OrgTimelineRepositoryFactory;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The per-tenant platform history behind {@code GET /api/admin/orgs/{orgId}/timeline} (slice 4,
 * {@code stories/platform_org_timeline.md}) — what has been done to this tenant, and by whom.
 *
 * <p>Answers the question an operator asks immediately after opening the tenant slice 3 found for
 * them. {@code GET /api/admin/orgs/{orgId}} is a rollup of current state; this is the account of
 * how it got there, merging the administrative ledger with the access one.
 */
public class PlatformOrgTimelineService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext dsl;
  private final OrgTimelineRepositoryFactory timelineRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public PlatformOrgTimelineService(
      DSLContext dsl,
      OrgTimelineRepositoryFactory timelineRepoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.dsl = dsl;
    this.timelineRepoFactory = timelineRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  /**
   * A page of the merged timeline.
   *
   * <p>{@code orgCreatedAt} is the tenant's own birth, carried on the <strong>envelope</strong>
   * rather than synthesized into the stream. Self-serve registration writes no audit row at all
   * ({@code AccountService} never calls {@link PlatformAuditService}), so most tenants have no
   * {@code ORG_CREATE} and the stream's oldest entry is routinely <em>not</em> the beginning.
   * Synthesizing a first entry would need an actor the row does not have — and inventing one is the
   * failure this whole console is written against. The client renders it as a terminal cap.
   */
  public record TimelinePage(
      List<OrgTimelineEntry> entries,
      long total,
      int page,
      int size,
      OffsetDateTime orgCreatedAt) {}

  /**
   * One page, newest-first. {@code page} is 0-based; {@code size} is clamped to {@code [1,
   * MAX_PAGE_SIZE]}.
   *
   * <p><strong>Unknown org is a 404, not an empty page.</strong> This is a lookup on a path segment
   * — the segment names a thing, and a thing that does not exist is absent, not empty. That is the
   * deliberate opposite of slice 2's {@code GET /api/admin/queues/{kind}?org_id=}, where an unknown
   * org yields an empty page because a <em>query parameter narrows a set</em>. Both are right; do
   * not "fix" one to match the other.
   *
   * @throws NotFoundException if no org has this id
   */
  public TimelinePage list(UUID orgId, int page, int size) {
    Org org =
        orgRepoFactory
            .create(dsl)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));

    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = PlatformOrgService.safeOffset(p, s);

    OrgTimelineRepository repo = timelineRepoFactory.create(dsl);
    List<OrgTimelineEntry> raw = repo.find(orgId, offset, s);
    long total = repo.count(orgId);

    return new TimelinePage(withActors(repo, raw), total, p, s, org.getCreatedAt());
  }

  /**
   * Resolve every actor on the page in <strong>one</strong> query — the batch-load precedent every
   * worklist in this codebase follows, never a lookup per row.
   *
   * <p>An id that resolves to nothing stays unresolved: the entry keeps its {@code actorId} and
   * carries no email. <strong>Never invent an actor.</strong> Attribution is the entire value of
   * this page, and a plausible-looking wrong name is worse than a gap — the client renders the raw
   * identifier or a system fallback, which is honest about what is known.
   */
  private List<OrgTimelineEntry> withActors(
      OrgTimelineRepository repo, List<OrgTimelineEntry> entries) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (OrgTimelineEntry e : entries) {
      if (e.actorId() != null) {
        ids.add(e.actorId());
      }
    }
    Map<UUID, String[]> actors = repo.actors(ids);

    List<OrgTimelineEntry> out = new ArrayList<>(entries.size());
    for (OrgTimelineEntry e : entries) {
      String[] a = e.actorId() == null ? null : actors.get(e.actorId());
      out.add(a == null ? e : e.withActor(a[0], a[1]));
    }
    return List.copyOf(out);
  }
}
