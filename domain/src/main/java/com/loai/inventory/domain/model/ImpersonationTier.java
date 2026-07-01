package com.loai.inventory.domain.model;

/**
 * Which authority a user exercised to start an impersonation overlay.
 *
 * <ul>
 *   <li>{@code PLATFORM} — a {@link SystemRole} ADMIN/SUPPORT acting across all orgs.
 *   <li>{@code ORG} — an {@link OrgRole#OWNER} acting only within a single org (the overlay's
 *       {@code org_roles} is scoped to that one org).
 * </ul>
 */
public enum ImpersonationTier {
  PLATFORM,
  ORG
}
