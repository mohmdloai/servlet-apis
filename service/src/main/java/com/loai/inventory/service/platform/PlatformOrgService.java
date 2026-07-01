package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.repository.OrgHealthRepository;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Platform-tier org operations: the cross-org read console (slice 2) and org lifecycle
 * (suspend/reactivate, slice 5). A platform ADMIN/SUPPORT can list every org and inspect one with
 * an operational rollup, holding no org role of their own; a platform ADMIN can additionally toggle
 * an org's suspension. Suspension only bites once {@code AuthzHelper.requireOrgAccess} enforces it
 * - so a toggle writes the org-active flag through the {@link OrgStatusService} cache immediately.
 */
public class PlatformOrgService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext dsl;
  private final OrgRepositoryFactory orgRepoFactory;
  private final OrgHealthRepository orgHealthRepo;
  private final PlatformAuditService audit;
  private final OrgStatusService orgStatus;

  public PlatformOrgService(
      DSLContext dsl,
      OrgRepositoryFactory orgRepoFactory,
      OrgHealthRepository orgHealthRepo,
      PlatformAuditService audit,
      OrgStatusService orgStatus) {
    this.dsl = dsl;
    this.orgRepoFactory = orgRepoFactory;
    this.orgHealthRepo = orgHealthRepo;
    this.audit = audit;
    this.orgStatus = orgStatus;
  }

  /** One org plus its distinct member count, for the list view. */
  public record OrgListItem(Org org, long memberCount) {}

  /** A page of orgs with the total count for pagination. */
  public record OrgPage(List<OrgListItem> items, long total, int page, int size) {}

  /** One org plus its full operational rollup, for the drill-down view. */
  public record OrgWithHealth(Org org, OrgHealth health) {}

  /**
   * Paged org list. {@code active} filters by status ({@code null} = all). {@code page} is 0-based;
   * {@code size} is clamped to {@code [1, MAX_PAGE_SIZE]}.
   */
  public OrgPage list(int page, int size, Boolean active) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = safeOffset(p, s);

    OrgRepository orgRepo = orgRepoFactory.create(dsl);
    List<Org> orgs = orgRepo.findAll(offset, s, active);
    long total = orgRepo.count(active);

    Map<UUID, Long> memberCounts =
        orgHealthRepo.memberCounts(orgs.stream().map(Org::getId).toList());
    List<OrgListItem> items =
        orgs.stream()
            .map(o -> new OrgListItem(o, memberCounts.getOrDefault(o.getId(), 0L)))
            .toList();
    return new OrgPage(items, total, p, s);
  }

  /** One org plus its health rollup. 404 if the org does not exist. */
  public OrgWithHealth getWithHealth(UUID orgId) {
    OrgRepository orgRepo = orgRepoFactory.create(dsl);
    Org org = orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
    return new OrgWithHealth(org, orgHealthRepo.health(orgId));
  }

  // ───────────────────────── lifecycle (slice 5) ─────────────────────────

  /**
   * Suspend an org: members lose access on their next request; the platform bypass is preserved.
   */
  public Org suspend(SecurityContext actor, Environment env, UUID orgId, String reason) {
    Org updated = toggle(actor, env, orgId, true, reason);
    orgStatus.invalidate(orgId); // next hot-path read load-through's the committed suspension
    return updated;
  }

  /** Reactivate a suspended org. */
  public Org reactivate(SecurityContext actor, Environment env, UUID orgId) {
    Org updated = toggle(actor, env, orgId, false, null);
    orgStatus.invalidate(orgId);
    return updated;
  }

  /**
   * {@code page * size} as an offset, computed in long and clamped to {@code Integer.MAX_VALUE} so
   * a huge page number can never overflow into a negative OFFSET (which Postgres rejects). A page
   * past the end simply returns no rows.
   */
  static int safeOffset(int page, int size) {
    long offset = (long) page * size;
    return offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset;
  }

  private Org toggle(
      SecurityContext actor, Environment env, UUID orgId, boolean suspended, String reason) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          Org org = orgRepoFactory.create(tx).setSuspension(orgId, suspended, reason);
          audit.recordInTx(
              tx,
              actor,
              env,
              suspended ? "ORG_SUSPEND" : "ORG_REACTIVATE",
              PlatformAuditEvent.Target.ORG,
              orgId,
              reason == null ? Map.of() : Map.of("reason", reason));
          return org;
        });
  }
}
