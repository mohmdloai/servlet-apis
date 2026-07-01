package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.AppUser;
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

  /** Grant a platform role. Idempotent (no-op if the row already exists). */
  void insertSystemRole(UUID userId, SystemRole role);

  /** Revoke a platform role. No-op if the user does not hold it. */
  void deleteSystemRole(UUID userId, SystemRole role);

  /** Revoke one org role. No-op if the user does not hold it. */
  void deleteOrgRole(UUID userId, UUID orgId, OrgRole role);

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

  /** Count users holding the given platform role - backs the last-admin guard. */
  long countUsersWithSystemRole(SystemRole role);
}
