package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.PasswordHasher;
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

  // ───────────────────────── reads ─────────────────────────

  public UserPage list(int page, int size, String emailQuery) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    UserRepository userRepo = userRepoFactory.create(dsl);
    List<AppUser> users = userRepo.findAll(p * s, s, emailQuery);
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
        authService.listSessions(userId).size());
  }

  // ───────────────────────── mutations ─────────────────────────

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
    String normalizedEmail = email.trim();
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
          AppUser created =
              userRepo.insert(new AppUser(null, normalizedEmail, hash, type, true, 0, null, null));
          audit.recordInTx(
              tx,
              actor,
              env,
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
    if (!active) {
      if (actor.actorId().equals(userId)) {
        throw new ValidationException("Cannot disable your own account");
      }
      assertNotLastAdmin(userId, "disable the last platform ADMIN");
    }

    AppUser updated =
        dsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              AppUser u = userRepoFactory.create(tx).setActive(userId, active);
              audit.recordInTx(
                  tx,
                  actor,
                  env,
                  active ? "USER_ENABLE" : "USER_DISABLE",
                  PlatformAuditEvent.Target.USER,
                  userId,
                  Map.of());
              return u;
            });

    if (!active) {
      authService.logoutAll(userId); // de-privilege → kill live tokens now
    }
    return updated;
  }

  public void grantSystemRole(
      SecurityContext actor, Environment env, UUID userId, SystemRole role) {
    ensureUserExists(userId);
    dsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          userRepoFactory.create(tx).insertSystemRole(userId, role);
          audit.recordInTx(
              tx,
              actor,
              env,
              "SYSTEM_ROLE_GRANT",
              PlatformAuditEvent.Target.USER,
              userId,
              Map.of("role", role.name()));
        });
    // A grant only widens authority, which the token picks up on next login/refresh - no revoke.
  }

  public void revokeSystemRole(
      SecurityContext actor, Environment env, UUID userId, SystemRole role) {
    if (role == SystemRole.ADMIN) {
      if (actor.actorId().equals(userId)) {
        throw new ValidationException("Cannot remove your own ADMIN role");
      }
      assertNotLastAdmin(userId, "remove the last platform ADMIN");
    }
    ensureUserExists(userId);
    dsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          userRepoFactory.create(tx).deleteSystemRole(userId, role);
          audit.recordInTx(
              tx,
              actor,
              env,
              "SYSTEM_ROLE_REVOKE",
              PlatformAuditEvent.Target.USER,
              userId,
              Map.of("role", role.name()));
        });
    authService.logoutAll(userId); // de-privilege → revoke now
  }

  public void grantOrgRole(
      SecurityContext actor, Environment env, UUID userId, UUID orgId, OrgRole role) {
    ensureUserExists(userId);
    ensureOrgExists(orgId);
    dsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          userRepoFactory.create(tx).insertOrgRole(userId, orgId, role);
          audit.recordInTx(
              tx,
              actor,
              env,
              "ORG_ROLE_GRANT",
              PlatformAuditEvent.Target.USER,
              userId,
              Map.of("org_id", orgId.toString(), "role", role.name()));
        });
  }

  public void revokeOrgRole(
      SecurityContext actor, Environment env, UUID userId, UUID orgId, OrgRole role) {
    ensureUserExists(userId);
    dsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          userRepoFactory.create(tx).deleteOrgRole(userId, orgId, role);
          audit.recordInTx(
              tx,
              actor,
              env,
              "ORG_ROLE_REVOKE",
              PlatformAuditEvent.Target.USER,
              userId,
              Map.of("org_id", orgId.toString(), "role", role.name()));
        });
    authService.logoutAll(userId); // de-privilege → revoke now
  }

  /** Force-set a user's password. Also revokes all their sessions. */
  public void resetPassword(
      SecurityContext actor, Environment env, UUID userId, String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new ValidationException("password is required");
    }
    ensureUserExists(userId);
    String hash = PasswordHasher.hash(rawPassword);
    dsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          userRepoFactory.create(tx).updatePasswordHash(userId, hash);
          audit.recordInTx(
              tx, actor, env, "PASSWORD_RESET", PlatformAuditEvent.Target.USER, userId, Map.of());
        });
    authService.logoutAll(userId); // an admin-forced reset invalidates existing sessions
  }

  // ───────────────────────── guards ─────────────────────────

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

  /** Block an action that would leave zero platform ADMINs, if {@code userId} is currently one. */
  private void assertNotLastAdmin(UUID userId, String actionDescription) {
    UserRepository userRepo = userRepoFactory.create(dsl);
    boolean targetIsAdmin = userRepo.findSystemRoles(userId).contains(SystemRole.ADMIN);
    if (targetIsAdmin && userRepo.countUsersWithSystemRole(SystemRole.ADMIN) <= 1) {
      throw new ValidationException("Cannot " + actionDescription);
    }
  }
}
