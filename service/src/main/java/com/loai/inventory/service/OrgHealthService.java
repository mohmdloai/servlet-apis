package com.loai.inventory.service;

import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.domain.repository.OrgHealthRepository;
import java.util.UUID;

/**
 * The tenant-facing door to an org's operational rollup — the org-scope counterpart of the platform
 * console's {@code GET /api/admin/orgs/{orgId}} health block. It delegates to the <em>same</em>
 * {@link OrgHealthRepository#health} the platform plane uses, so a tenant and a platform operator
 * can never see divergent numbers; only the authorization context differs (org membership here, a
 * platform {@code SystemRole} there). See {@code stories/org_health_rollup.md}.
 */
public final class OrgHealthService {

  private final OrgHealthRepository orgHealthRepo;

  public OrgHealthService(OrgHealthRepository orgHealthRepo) {
    this.orgHealthRepo = orgHealthRepo;
  }

  /** The org's members / pending-payment orders / open disputes / unallocated-payments rollup. */
  public OrgHealth health(UUID orgId) {
    return orgHealthRepo.health(orgId);
  }
}
