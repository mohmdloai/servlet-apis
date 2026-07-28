package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.UpstreamFailureException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.UserOrgRole;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
  private final CredentialTokenService credentialTokenService;
  private final AuthMailer authMailer;

  public UserAdminService(
      DSLContext dsl,
      UserRepositoryFactory userRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      AuthService authService,
      PlatformAuditService audit,
      CredentialTokenService credentialTokenService,
      AuthMailer authMailer) {
    this.dsl = dsl;
    this.userRepoFactory = userRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.authService = authService;
    this.audit = audit;
    this.credentialTokenService = credentialTokenService;
    this.authMailer = authMailer;
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

  /**
   * Grant {@code role} in {@code orgId} — refused with a <b>409</b> when that org is {@link
   * OrgStatus#PENDING}.
   *
   * <p><b>The refusal exists because this endpoint can permanently brick a tenant, while trying to
   * help it.</b> {@code OrgRepository.activateRegistrationPendingOrgs} — the UPDATE that {@code
   * AccountService.verifyEmail} runs, and the <em>only</em> thing that takes a self-serve org live
   * — carries {@code AND NOT EXISTS (any user_org_role row for this org with user_id <> ownerId)}.
   * Add a second <em>person</em> to a PENDING org and that UPDATE matches zero rows forever: the
   * owner clicks their link, {@code email_verified_at} is stamped, and the tenant stays PENDING
   * with no path out. Reactivate is refused on a pending org (correctly — it clears a suspension
   * that was never applied), and {@code unverified-account-purge} never touches a multi-member org,
   * so the bricked tenant is permanent by two independent rules. This endpoint is the only
   * reachable way in, because the org-plane roster write requires OWNER and this org's owner is
   * login-blocked until they verify — so the plausible path is an ADMIN adding a colleague <em>to
   * help a stuck signup</em>.
   *
   * <p><b>The guard is deliberately wider than the brick.</b> The {@code NOT EXISTS} excludes rows
   * whose {@code user_id} is the owner's, so granting a second role to the <em>same</em> owner does
   * not actually brick anything. Refusing it anyway costs nothing and is independently right: a
   * member of a PENDING org cannot use it regardless, because {@code requireOrgAccess} 403s them.
   * Narrowing this to "only when the grantee is someone else" would buy an edge case nobody wants
   * and make the rule harder to state than the invariant it protects.
   *
   * <p>SUSPENDED orgs still accept grants — staffing a suspended tenant ahead of reactivation is
   * legitimate and nothing about it is unrecoverable. The check reads {@link OrgStatus}, never a
   * re-derived boolean; that boolean <em>was</em> the ambiguity slice 1.5 removed.
   *
   * <p>Deliberately <b>not</b> fixed by loosening the activation predicate: its {@code NOT EXISTS}
   * is what stops an unrelated inactive org from going live just because its owner happened to
   * verify, and replacing it needs a "born at this user's registration" column that does not exist.
   * Close the door; do not widen the room.
   */
  public void grantOrgRole(
      SecurityContext actor, Environment env, UUID userId, UUID orgId, OrgRole role) {
    ensureUserExists(userId);
    Org org = ensureOrgExists(orgId);
    if (OrgStatus.of(org) == OrgStatus.PENDING) {
      throw new ConflictException(
          "This tenant is waiting on its owner's email verification. Adding a second member now"
              + " would permanently prevent it from activating.");
    }
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

  /** What a successful resend reports back: the address it went to, and when the link dies. */
  public record ResendResult(String email, OffsetDateTime expiresAt) {}

  /**
   * Re-send a user's email-verification link on the platform's behalf — the console's answer to
   * "they signed up and cannot log in".
   *
   * <p>A PENDING org means exactly one thing: its owner never clicked the link. {@code
   * AccountService.verifyEmail} stamps {@code email_verified_at} and then calls {@code
   * activateRegistrationPendingOrgs}, so <em>verifying the email is literally what activates the
   * tenant</em> — which makes this the action that gets a stuck signup unstuck.
   *
   * <p><b>Every outcome names its cause, unlike the anonymous {@code POST
   * /api/auth/resend-verification}.</b> That endpoint answers a uniform {@code 200} and swallows
   * every failure because it is unauthenticated and a distinguishable response is an
   * account-enumeration oracle. Here the caller is an authenticated ADMIN who was just shown the
   * address by the page they clicked from, so there is nothing left to protect and a great deal to
   * report: unknown user 404, already-verified 409, disabled 409 (a disabled account must not be
   * handed a link that logs it in), an {@code orgId} the user does not own 400, and — the one that
   * matters most — a failed hand-off to the mail provider as {@link UpstreamFailureException}
   * (502), never as a success. An operator told "sent" for mail that never left spends the next
   * hour on the wrong hypothesis; that is the failure this whole slice exists to prevent, so the
   * send goes through {@link AuthMailer#sendVerifyEmailOrThrow} rather than the swallowing variant.
   *
   * <p><b>{@code orgId} is validated, not trusted.</b> Present means the operator acted from a
   * tenant's page and the rescue belongs on that tenant's timeline (the per-tenant question V76's
   * column was added for: <em>who got this tenant unstuck, and when</em>) — but only after checking
   * the user actually holds OWNER there. Absent means the operator acted from {@code
   * /admin/users/{id}} and named no tenant, so the audit row gets an explicit {@code null}: a
   * resend targets a person, and a person is not a tenant event. An audit column you can point
   * anywhere is worse than a null one.
   *
   * <p><b>No rate-limit bucket, deliberately.</b> {@code requireAdmin} plus an audit row per call
   * is the control. A per-IP bucket sized for anonymous abuse (the anonymous path shares {@code
   * AUTH_FORGOT_LIMIT}) would throttle a support desk clearing a backlog of stuck signups while
   * protecting nothing an ADMIN could not already do. Stated here so the omission reads as a
   * decision rather than an oversight, and so nobody adds one by reflex.
   *
   * <p>The audit row is written <b>whether or not delivery succeeds</b>, carrying {@code delivered}
   * — because the consequential state change is the mint, which supersedes every prior live link
   * (only the latest redeems). Dropping the row on a failed send would leave the ledger silent
   * about a still-valid link having just been killed; writing it without {@code delivered} would
   * let a tenant timeline imply a delivery that never happened. Both are the console lying.
   */
  public ResendResult resendVerification(
      SecurityContext actor, Environment env, UUID userId, UUID orgId, OffsetDateTime now) {
    UserRepository userRepo = userRepoFactory.create(dsl);
    AppUser user =
        userRepo.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (user.getEmailVerifiedAt() != null) {
      throw new ConflictException("This account is already verified");
    }
    if (!user.isActive()) {
      throw new ConflictException("This account is disabled");
    }
    if (orgId != null) {
      ensureOrgExists(orgId);
      if (!userRepo.findRolesInOrg(userId, orgId).contains(OrgRole.OWNER)) {
        throw new ValidationException("This user does not own that org");
      }
    }

    credentialTokenService.invalidateActive(userId, AppUserTokenPurpose.EMAIL_VERIFY, now);
    String rawToken =
        credentialTokenService.mintAutonomous(userId, AppUserTokenPurpose.EMAIL_VERIFY, now);
    OffsetDateTime expiresAt =
        credentialTokenService.expiresAt(AppUserTokenPurpose.EMAIL_VERIFY, now);

    boolean delivered = true;
    RuntimeException sendFailure = null;
    try {
      authMailer.sendVerifyEmailOrThrow(
          user.getEmail(), credentialTokenService.verifyUrl(rawToken));
    } catch (RuntimeException e) {
      delivered = false;
      sendFailure = e;
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("email", user.getEmail());
    if (orgId != null) {
      detail.put("org_id", orgId.toString());
    }
    detail.put("delivered", delivered);
    audit.record(
        actor, env, orgId, "EMAIL_VERIFY_RESEND", PlatformAuditEvent.Target.USER, userId, detail);

    if (!delivered) {
      log.warn("Verification link minted but not delivered for user id={}", userId, sendFailure);
      throw new UpstreamFailureException(
          "The verification link was created but could not be emailed. Nothing was sent — try"
              + " again.",
          sendFailure);
    }
    log.info("Verification link resent for user id={} org={}", userId, orgId);
    return new ResendResult(user.getEmail(), expiresAt);
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

  private Org ensureOrgExists(UUID orgId) {
    OrgRepository orgRepo = orgRepoFactory.create(dsl);
    return orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
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
