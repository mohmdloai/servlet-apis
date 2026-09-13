package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.UserOrgRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserRepository {
  Optional<AppUser> findById(UUID id);

  Optional<AppUser> findByEmail(String email);

  AppUser insert(AppUser user);

  AppUser update(AppUser user);

  int incrementTokenVersion(UUID userId);

  int getTokenVersion(UUID userId);

  List<UserOrgRole> findOrgRoles(UUID userId);

  Set<SystemRole> findSystemRoles(UUID userId);

  /** Atomic insert of a user_org_role row. Used by OrgService when creating an org. */
  void insertOrgRole(UUID userId, UUID orgId, OrgRole role);

  // ── Platform user/role administration (see docs/platform-admin-plan.md, slice 3) ──

  /**
   * Ids of every <em>active</em> user holding any of {@code roles} in {@code orgId} (distinct — a
   * user with two qualifying roles appears once). Backs notification fan-out to org staff. An empty
   * {@code roles} set returns no ids.
   */
  Set<UUID> findActiveUserIdsByOrgAndRoles(UUID orgId, Set<OrgRole> roles);

  /** Grant a platform role. Idempotent (no-op if the row already exists). */
  void insertSystemRole(UUID userId, SystemRole role);

  /** Revoke a platform role. Returns the number of rows deleted (0 if the user lacked it). */
  int deleteSystemRole(UUID userId, SystemRole role);

  /** Revoke one org role. Returns the number of rows deleted (0 if the user lacked it). */
  int deleteOrgRole(UUID userId, UUID orgId, OrgRole role);

  // ── Org-scoped membership (see stories/09_st_org_settings.md) ──

  /**
   * Every member of {@code orgId}: each {@code app_user} that holds at least one role there, with
   * its full role set aggregated. Ordered by email. Backs {@code GET /api/orgs/{orgId}/members}.
   */
  List<OrgMember> findMembers(UUID orgId);

  /**
   * One page of {@code orgId}'s members, ordered by email — paginated over <em>distinct users</em>
   * (a member with several roles is one row, never split across a page boundary). Backs the
   * paginated {@code GET /api/orgs/{orgId}/members} ({@code stories/org_health_rollup.md}).
   */
  List<OrgMember> findMembers(UUID orgId, int offset, int limit);

  /** Distinct member count for {@code orgId} — the {@code total} for the paginated roster. */
  long countMembers(UUID orgId);

  /**
   * The roles {@code userId} holds in {@code orgId} (empty ⇒ not a member). Used to decide whether
   * a PUT edits an existing member and whether a change is a demotion.
   */
  Set<OrgRole> findRolesInOrg(UUID userId, UUID orgId);

  /**
   * Lock and return the ids of every user holding OWNER in {@code orgId}, taking a {@code FOR
   * UPDATE} row lock so concurrent de-privileges serialize. Backs the last-owner guard (an org must
   * always keep at least one OWNER). Must be called on a transaction-bound repository.
   */
  Set<UUID> ownerIdsForUpdate(UUID orgId);

  /**
   * Remove every role {@code userId} holds in {@code orgId}. Returns the number of rows deleted.
   */
  int deleteAllOrgRoles(UUID userId, UUID orgId);

  /**
   * Paged user list, optionally filtered by a case-insensitive email prefix ({@code null} = all).
   */
  List<AppUser> findAll(int offset, int limit, String emailQuery);

  /** Total user count matching the same optional email-prefix filter. */
  long countAll(String emailQuery);

  /** Toggle a user's enabled flag; returns the updated user. */
  AppUser setActive(UUID userId, boolean active);

  /** Overwrite a user's password hash. */
  void updatePasswordHash(UUID userId, String passwordHash);

  /**
   * Lock and return the ids of every currently *active* platform ADMIN, taking a {@code FOR UPDATE}
   * row lock so concurrent de-privileges serialize. Backs the last-admin guard: it counts only
   * usable (active) admins and, by locking, makes the guard's check-then-mutate atomic. Must be
   * called on a transaction-bound repository.
   */
  Set<UUID> activeAdminIdsForUpdate();

  /**
   * Every active user holding a platform role (ADMIN or SUPPORT) — the support desk's fan-out
   * ({@code stories/support_tickets.md}): who is told when a merchant opens or updates a ticket. No
   * lock: a notification recipient set is a snapshot, not a guard.
   */
  Set<UUID> activeDeskUserIds();

  // ── Register verify-to-activate (story 88) ──

  /**
   * Stamp {@code email_verified_at = at} iff it is still NULL (idempotent — a later inbox proof
   * never rewrites the first). Returns rows updated (0 = already verified or unknown user).
   */
  int markEmailVerified(UUID userId, java.time.OffsetDateTime at);

  /**
   * Ids of unverified accounts the purge job may delete: {@code email_verified_at IS NULL}, created
   * before {@code cutoff}, and holding <b>no live EMAIL_VERIFY token</b> (never yank an account
   * whose emailed link could still be clicked). Oldest first, capped at {@code limit}.
   */
  List<UUID> findPurgeableUnverified(java.time.OffsetDateTime cutoff, int limit);

  /**
   * Ids of orgs where {@code userId} is the <em>only</em> member (no other user holds any role
   * there). The purge job deletes these alongside the account — an org whose sole owner never
   * logged in can hold no business data.
   */
  List<UUID> soleMemberOrgIds(UUID userId);

  /**
   * Hard-delete an app_user row (magic tokens cascade via FK). Returns rows deleted. Purge-job only
   * — everywhere else deactivation is the correct verb.
   */
  int deleteUser(UUID userId);
}
