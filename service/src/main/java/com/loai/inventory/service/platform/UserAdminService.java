package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.UserOrgRole;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.auth.AuthService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform user &amp; role administration (see {@code docs/platform-admin-plan.md}, slice 3). This
 * is how a new ADMIN or SUPPORT is minted, and how users are enabled/disabled, re-roled, and
 * password-reset - all without touching the database directly.
 *
 * <p>Two invariants are load-bearing here:
 *
 * <ul>
 *   <li><b>De-privilege revokes now.</b> Disabling a user or removing a role calls {@link
 *       AuthService#logoutAll} so the old {@code org_roles}/{@code system_roles} baked into any
 *       live access token die immediately, rather than lingering for up to the access-token TTL.
 *   <li><b>No self-lockout / last-admin.</b> An ADMIN cannot strip their own ADMIN role or disable
 *       themselves, and the system refuses to remove the final platform ADMIN.
 * </ul>
 *
 * Every mutation writes a {@code platform_audit} row inside the same transaction as the change.
 */
public class UserAdminService {

  private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext dsl;
  private final UserRepositoryFactory userRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final AuthService authService;
  private final PlatformAuditService audit;

  public UserAdminService(
      DSLContext dsl,
      UserRepositoryFactory userRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      AuthService authService,
      PlatformAuditService audit) {
    this.dsl = dsl;
    this.userRepoFactory = userRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.authService = authService;
    this.audit = audit;
  }

  public record UserPage(List<AppUser> users, long total, int page, int size) {}

  public record UserDetail(
      AppUser user,
      Set<SystemRole> systemRoles,
      List<UserOrgRole> orgRoles,
      long activeSessionCount) {}

  public UserPage list(int page, int size, String emailQuery) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    // Compute the offset in long and clamp so a huge page can't overflow to a negative OFFSET.
    long rawOffset = (long) p * s;
    int offset = rawOffset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rawOffset;
    UserRepository userRepo = userRepoFactory.create(dsl);
    List<AppUser> users = userRepo.findAll(offset, s, emailQuery);
    long total = userRepo.countAll(emailQuery);
    return new UserPage(users, total, p, s);
  }

  public UserDetail get(UUID userId) {
    UserRepository userRepo = userRepoFactory.create(dsl);
    AppUser user =
        userRepo.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    return new UserDetail(
        user,
        userRepo.findSystemRoles(userId),
        userRepo.findOrgRoles(userId),
        authService.countSessions(userId));
  }

  /**
   * Create a user. {@code actorType} is restricted to USER/SERVICE (SYSTEM/MIGRATION are internal).
   * A blank password stores an unusable hash - the account cannot log in until a password reset.
   */
  public AppUser createUser(
      SecurityContext actor,
      Environment env,
      String email,
      String rawPassword,
      ActorType actorType) {
    if (email == null || email.isBlank()) {
      throw new ValidationException("email is required");
    }
    ActorType type = actorType == null ? ActorType.USER : actorType;
    if (type != ActorType.USER && type != ActorType.SERVICE) {
      throw new ValidationException("actor_type must be USER or SERVICE");
    }
    String normalizedEmail = Text.normalizeEmail(email);
    String hash =
        (rawPassword == null || rawPassword.isBlank())
            ? PasswordHasher.hash("!" + UUID.randomUUID()) // unusable until reset
            : PasswordHasher.hash(rawPassword);

    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          UserRepository userRepo = userRepoFactory.create(tx);
          if (userRepo.findByEmail(normalizedEmail).isPresent()) {
            throw new ConflictException("A user with that email already exists");
          }
          AppUser toInsert = new AppUser(null, normalizedEmail, hash, type, true, 0, null, null);
          // Born verified (story 88): the admin plane vouches, and the reset/invite flow that
          // hands over the password re-proves the inbox anyway. Platform-created users must
          // never be 403-blocked at login.
          toInsert.setEmailVerifiedAt(java.time.OffsetDateTime.now());
          AppUser created = userRepo.insert(toInsert);
          // null: an admin-plane account creation belongs to no tenant. The provisioning path's
          // USER_CREATE (PlatformOrgService) does carry an org — same verb, different event.
          audit.recordInTx(
              tx,
              actor,
              env,
              null,
              "USER_CREATE",
              PlatformAuditEvent.Target.USER,
              created.getId(),
              Map.of("email", normalizedEmail, "actor_type", type.name()));
          log.info(
              "Platform user created id={} email={} by={}",
              created.getId(),
              normalizedEmail,
              actor.actorId());
          return created;
        });
  }

  /** Enable or disable a user. Disabling forces an immediate global logout. */
  public AppUser setActive(SecurityContext actor, Environment env, UUID userId, boolean active) {
    if (!active && actor.actorId().equals(userId)) {
      throw new ValidationException("Cannot disable your own account");
    }

    // The last-admin guard, the flag flip, the audit row, and the token_version bump all commit as
    // one transaction: the guard locks the active-admin rows (serializing concurrent disables) and
    // the version bump rides along so a disabled user's live token dies atomically with the change.
    Bumped result =
        dsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              UserRepository repo = userRepoFactory.create(tx);
              if (!active) {
                assertNotLastActiveAdmin(repo, userId, "disable the last platform ADMIN");
                assertNotSoleOwnerAnywhere(repo, userId);
              }
              AppUser u = repo.setActive(userId, active);
              // null: enabling/disabling an identity is platform-wide. Attributing it to an org
              // the user happens to belong to would claim that tenant was touched when it was not.
              audit.recordInTx(
                  tx,
                  actor,
                  env,
                  null,
                  active ? "USER_ENABLE" : "USER_DISABLE",
                  PlatformAuditEvent.Target.USER,
                  userId,
                  Map.of());
              return new Bumped(u, active ? null : repo.incrementTokenVersion(userId));
            });

    if (result.newTokenVersion() != null) {
      authService.propagateLogoutAll(userId, result.newTokenVersion()); // de-privilege revokes now
    }
    return result.user();
  }

  public void grantSystemRole(
      SecurityContext actor, Environment env, UUID userId, SystemRole role) {
    ensureUserExists(userId);
    dsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          userRepoFactory.create(tx).insertSystemRole(userId, role);
          // null: a system role is authority over the platform, not over any one tenant.
          audit.recordInTx(
              tx,
              actor,
              env,
              null,
              "SYSTEM_ROLE_GRANT",
              PlatformAuditEvent.Target.USER,
              userId,
              Map.of("role", role.name()));
        });
    // A grant only widens authority, which the token picks up on next login/refresh - no revoke.
  }

  public void revokeSystemRole(
      SecurityContext actor, Environment env, UUID userId, SystemRole role) {
    if (role == SystemRole.ADMIN && actor.actorId().equals(userId)) {
      throw new ValidationException("Cannot remove your own ADMIN role");
    }
    ensureUserExists(userId);
    Integer newVersion =
        dsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              UserRepository repo = userRepoFactory.create(tx);
              // Guard inside the txn (locking) so the count-then-delete is atomic against a
              // concurrent revoke of a different admin.
              if (role == SystemRole.ADMIN) {
                assertNotLastActiveAdmin(repo, userId, "remove the last platform ADMIN");
              }
              int deleted = repo.deleteSystemRole(userId, role);
              if (deleted == 0) {
                return null; // idempotent no-op: user never held the role, so nothing to audit
              }
              // null: platform-wide, same as the grant.
              audit.recordInTx(
                  tx,
                  actor,
                  env,
                  null,
                  "SYSTEM_ROLE_REVOKE",
                  PlatformAuditEvent.Target.USER,
                  userId,
                  Map.of("role", role.name()));
              return repo.incrementTokenVersion(userId);
            });
    if (newVersion != null) {
      authService.propagateLogoutAll(userId, newVersion); // de-privilege revokes now
    }
  }

  public void grantOrgRole(
      SecurityContext actor, Environment env, UUID userId, UUID orgId, OrgRole role) {
    ensureUserExists(userId);
    ensureOrgExists(orgId);
    dsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          userRepoFactory.create(tx).insertOrgRole(userId, orgId, role);
          // The tenant, explicitly. This row targets the USER, so before V76 it was invisible to
          // any per-tenant read — and "who was given access to this tenant, and when" is the whole
          // of an access-review question, not a footnote. This is the slice.
          audit.recordInTx(
              tx,
              actor,
              env,
              orgId,
              "ORG_ROLE_GRANT",
              PlatformAuditEvent.Target.USER,
              userId,
              Map.of("org_id", orgId.toString(), "role", role.name()));
        });
  }

  public void revokeOrgRole(
      SecurityContext actor, Environment env, UUID userId, UUID orgId, OrgRole role) {
    ensureUserExists(userId);
    Integer newVersion =
        dsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              UserRepository repo = userRepoFactory.create(tx);
              if (role == OrgRole.OWNER) {
                // Mirror the OWNER-plane last-owner invariant (MemberService.guardLastOwner): the
                // platform plane must not be able to strip an org's only OWNER and leave it
                // ownerless either. Runs inside the txn so ownerIdsForUpdate's row lock makes the
                // check-then-delete atomic against a concurrent revoke of a different owner.
                Set<UUID> owners = repo.ownerIdsForUpdate(orgId);
                if (owners.contains(userId) && owners.size() == 1) {
                  throw new ConflictException("Org must have at least one owner");
                }
              }
              int deleted = repo.deleteOrgRole(userId, orgId, role);
              if (deleted == 0) {
                return null; // idempotent no-op: user never held the role, so no audit / no logout
              }
              audit.recordInTx(
                  tx,
                  actor,
                  env,
                  orgId,
                  "ORG_ROLE_REVOKE",
                  PlatformAuditEvent.Target.USER,
                  userId,
                  Map.of("org_id", orgId.toString(), "role", role.name()));
              return repo.incrementTokenVersion(userId);
            });
    if (newVersion != null) {
      authService.propagateLogoutAll(userId, newVersion); // de-privilege revokes now
    }
  }

  /** Force-set a user's password. Also revokes all their sessions. */
  public void resetPassword(
      SecurityContext actor, Environment env, UUID userId, String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new ValidationException("password is required");
    }
    ensureUserExists(userId);
    String hash = PasswordHasher.hash(rawPassword);
    int newVersion =
        dsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              UserRepository repo = userRepoFactory.create(tx);
              repo.updatePasswordHash(userId, hash);
              // null: a credential belongs to the identity, not to any tenant it has roles in.
              audit.recordInTx(
                  tx,
                  actor,
                  env,
                  null,
                  "PASSWORD_RESET",
                  PlatformAuditEvent.Target.USER,
                  userId,
                  Map.of());
              return repo.incrementTokenVersion(userId);
            });
    // an admin-forced reset invalidates existing sessions
    authService.propagateLogoutAll(userId, newVersion);
  }

  private void ensureUserExists(UUID userId) {
    if (userRepoFactory.create(dsl).findById(userId).isEmpty()) {
      throw new NotFoundException("User", userId);
    }
  }

  private void ensureOrgExists(UUID orgId) {
    OrgRepository orgRepo = orgRepoFactory.create(dsl);
    if (orgRepo.findById(orgId).isEmpty()) {
      throw new NotFoundException("Org", orgId);
    }
  }

  /**
   * Block an action that would leave zero *usable* (active) platform ADMINs. Must run on the
   * transaction-bound {@code repo}: {@link UserRepository#activeAdminIdsForUpdate()} takes a row
   * lock so the check and the following mutation are atomic against a concurrent de-privilege of a
   * different admin, and it counts only active admins so a previously-disabled admin can never make
   * this guard pass by inflating the tally.
   */
  private void assertNotLastActiveAdmin(
      UserRepository repo, UUID userId, String actionDescription) {
    Set<UUID> activeAdmins = repo.activeAdminIdsForUpdate();
    if (activeAdmins.contains(userId) && activeAdmins.size() == 1) {
      throw new ValidationException("Cannot " + actionDescription);
    }
  }

  /**
   * Block disabling a user who is the <em>sole</em> OWNER of any org - a second vector to the same
   * ownerless state the {@code revokeOrgRole} guard closes (a disabled owner cannot log in, so the
   * org is effectively ownerless). For each org the user owns, {@link
   * UserRepository#ownerIdsForUpdate} takes a row lock so the check is atomic against a concurrent
   * ownership change. Reassigning ownership first is the operator's path.
   */
  private void assertNotSoleOwnerAnywhere(UserRepository repo, UUID userId) {
    for (UserOrgRole r : repo.findOrgRoles(userId)) {
      if (r.getRole() != OrgRole.OWNER) {
        continue;
      }
      Set<UUID> owners = repo.ownerIdsForUpdate(r.getOrgId());
      if (owners.size() == 1 && owners.contains(userId)) {
        throw new ConflictException(
            "Cannot disable the sole owner of org "
                + r.getOrgId()
                + "; reassign ownership before disabling this user");
      }
    }
  }

  /** Carries a mutation's result plus the new token_version when the change forced a logout. */
  private record Bumped(AppUser user, Integer newTokenVersion) {}
}
