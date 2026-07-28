package com.loai.inventory.domain.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistence for {@code org_milestone} (slice 7, {@code stories/platform_tenant_funnel.md}) — an
 * append-only, stamped-once table. The single write method is named {@code insertIfAbsent} rather
 * than {@code insert} on purpose: every caller is a milestone that may already be recorded (a
 * republish is not a first publish), and idempotence here comes from the {@code (org_id,
 * milestone)} PRIMARY KEY via {@code ON CONFLICT DO NOTHING} — not from a service-level check a
 * future caller could forget to make.
 */
public interface OrgMilestoneRepository {

  /**
   * Stamp {@code stage} reached for {@code orgId} at {@code reachedAt}, unless it already is. The
   * first stamp wins; every later call for the same {@code (orgId, milestone)} is a no-op.
   *
   * @return true when a row was actually inserted, false when the milestone was already stamped.
   */
  boolean insertIfAbsent(UUID orgId, String milestone, OffsetDateTime reachedAt);
}
