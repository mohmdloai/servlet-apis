package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.OrgHealth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Org-less aggregate reads for the platform console. These deliberately sit outside the org-scoped
 * repositories: a platform operator queries across tenants, so there is no single {@code orgId}
 * threaded through the request the way every business path has one.
 */
public interface OrgHealthRepository {
  /** Full operational rollup for one org. */
  OrgHealth health(UUID orgId);

  /** Distinct member counts for many orgs in one grouped query (avoids N+1 on the list view). */
  Map<UUID, Long> memberCounts(List<UUID> orgIds);
}
