package com.loai.inventory.service.auth;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.ImpersonationEvent;
import com.loai.inventory.domain.model.ImpersonationTier;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.UserOrgRole;
import com.loai.inventory.domain.repository.ImpersonationEventRepository;
import com.loai.inventory.domain.repository.UserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final int MIN_PASSWORD_LENGTH = 8;

  private final UserRepository userRepo;
  private final RefreshTokenStore refreshTokenStore;
  private final JwtUtil jwtUtil;
  private final ImpersonationEventRepository impersonationEventRepo;
  private final long impersonationTtlMillis;

  public AuthService(
      UserRepository userRepo,
      RefreshTokenStore refreshTokenStore,
      JwtUtil jwtUtil,
      ImpersonationEventRepository impersonationEventRepo,
      long impersonationTtlMillis) {
    this.userRepo = userRepo;
    this.refreshTokenStore = refreshTokenStore;
    this.jwtUtil = jwtUtil;
    this.impersonationEventRepo = impersonationEventRepo;
    this.impersonationTtlMillis = impersonationTtlMillis;
  }

  public record LoginResult(
      String accessToken, String refreshToken, long expiresIn, AppUser user) {}

  public record ImpersonationResult(
      String accessToken,
      long expiresIn,
      UUID impersonatorId,
      ImpersonationTier tier,
      boolean readOnly) {}

  public LoginResult login(String email, String rawPassword, String deviceInfo, String sourceIp) {
    AppUser user =
        userRepo
            .findByEmail(email)
            .orElseThrow(() -> new AuthenticationException("Invalid email or password"));
    // no point doing crypto work (password hashing) for a disabled account.
    if (!user.isActive()) {
      throw new AuthenticationException("Account is disabled");
    }

    if (!PasswordHasher.verify(rawPassword, user.getPasswordHash())) {
      throw new AuthenticationException("Invalid email or password");
    }

    List<UserOrgRole> orgRoleList = userRepo.findOrgRoles(user.getId());
    Map<UUID, Set<String>> orgRoles = buildOrgRolesMap(orgRoleList);
    Set<String> systemRoles = buildSystemRolesSet(userRepo.findSystemRoles(user.getId()));
    int tokenVersion = userRepo.getTokenVersion(user.getId());

    UUID familyId = UUID.randomUUID();
    String accessToken =
        jwtUtil.generateAccessToken(
            user.getId(),
            user.getActorType().name(),
            orgRoles,
            systemRoles,
            Set.of(),
            tokenVersion,
            familyId);

    String rawRefreshToken = UUID.randomUUID().toString();
    String tokenHash = RefreshTokenStore.hashToken(rawRefreshToken);

    refreshTokenStore.store(
        tokenHash,
        new RefreshTokenStore.TokenData(
            user.getId(), familyId, deviceInfo, sourceIp, Instant.now()));

    refreshTokenStore.cacheTokenVersion(user.getId(), tokenVersion);

    log.info("User logged in: id={} email={}", user.getId(), user.getEmail());
    return new LoginResult(accessToken, rawRefreshToken, jwtUtil.getAccessTtlMillis() / 1000, user);
  }

  /**
   * Self-service password change: verify the current password, rotate the hash, and revoke every
   * existing session (bump {@code token_version} + logout-all) so the change signs the user out of
   * all *other* devices. To keep the device that just changed the password signed in, a fresh
   * session (access + refresh) is minted at the new version and returned for the servlet to cookie.
   * Blocked upstream while impersonating — an overlay must never rotate the target's credential.
   */
  public LoginResult changePassword(
      UUID userId, String currentPassword, String newPassword, String deviceInfo, String sourceIp) {
    if (currentPassword == null || newPassword == null) {
      throw new ValidationException("current_password and new_password are required");
    }
    AppUser user =
        userRepo.findById(userId).orElseThrow(() -> new AuthenticationException("User not found"));
    if (!user.isActive()) {
      throw new AuthenticationException("Account is disabled");
    }
    if (!PasswordHasher.verify(currentPassword, user.getPasswordHash())) {
      throw new ValidationException("current password is incorrect");
    }
    if (newPassword.length() < MIN_PASSWORD_LENGTH) {
      throw new ValidationException(
          "new password must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
    if (PasswordHasher.verify(newPassword, user.getPasswordHash())) {
      throw new ValidationException("new password must differ from the current password");
    }

    // Rotate the hash, then bump token_version + revoke every refresh family (all other devices
    // die).
    // revokeAllForUser runs inside propagateLogoutAll BEFORE we store the new family below, so the
    // current device's fresh session survives.
    userRepo.updatePasswordHash(userId, PasswordHasher.hash(newPassword));
    int newVersion = userRepo.incrementTokenVersion(userId);
    propagateLogoutAll(userId, newVersion);

    Map<UUID, Set<String>> orgRoles = buildOrgRolesMap(userRepo.findOrgRoles(userId));
    Set<String> systemRoles = buildSystemRolesSet(userRepo.findSystemRoles(userId));
    UUID familyId = UUID.randomUUID();
    String accessToken =
        jwtUtil.generateAccessToken(
            userId,
            user.getActorType().name(),
            orgRoles,
            systemRoles,
            Set.of(),
            newVersion,
            familyId);
    String rawRefreshToken = UUID.randomUUID().toString();
    refreshTokenStore.store(
        RefreshTokenStore.hashToken(rawRefreshToken),
        new RefreshTokenStore.TokenData(userId, familyId, deviceInfo, sourceIp, Instant.now()));
    refreshTokenStore.cacheTokenVersion(userId, newVersion);

    log.info("User changed password: id={}", userId);
    return new LoginResult(accessToken, rawRefreshToken, jwtUtil.getAccessTtlMillis() / 1000, user);
  }

  public LoginResult refresh(String rawRefreshToken, String sourceIp) {
    String tokenHash = RefreshTokenStore.hashToken(rawRefreshToken);
    Optional<RefreshTokenStore.TokenData> existing = refreshTokenStore.find(tokenHash);

    if (existing.isEmpty()) {
      log.warn("Refresh token not found — possible reuse of revoked token");
      throw new AuthenticationException("Invalid refresh token");
    }

    RefreshTokenStore.TokenData data = existing.get();

    refreshTokenStore.revoke(tokenHash);

    AppUser user =
        userRepo
            .findById(data.userId())
            .orElseThrow(() -> new AuthenticationException("User not found"));

    if (!user.isActive()) {
      refreshTokenStore.revokeFamily(data.familyId(), data.userId());
      throw new AuthenticationException("Account is disabled");
    }

    List<UserOrgRole> orgRoleList = userRepo.findOrgRoles(user.getId());
    Map<UUID, Set<String>> orgRoles = buildOrgRolesMap(orgRoleList);
    Set<String> systemRoles = buildSystemRolesSet(userRepo.findSystemRoles(user.getId()));
    int tokenVersion = userRepo.getTokenVersion(user.getId());

    String accessToken =
        jwtUtil.generateAccessToken(
            user.getId(),
            user.getActorType().name(),
            orgRoles,
            systemRoles,
            Set.of(),
            tokenVersion,
            data.familyId());

    String newRawRefreshToken = UUID.randomUUID().toString();
    String newTokenHash = RefreshTokenStore.hashToken(newRawRefreshToken);

    refreshTokenStore.store(
        newTokenHash,
        new RefreshTokenStore.TokenData(
            user.getId(), data.familyId(), data.deviceInfo(), sourceIp, Instant.now()));

    refreshTokenStore.cacheTokenVersion(user.getId(), tokenVersion);

    log.debug("Refreshed tokens for user id={}", user.getId());
    return new LoginResult(
        accessToken, newRawRefreshToken, jwtUtil.getAccessTtlMillis() / 1000, user);
  }

  public void logout(String rawRefreshToken) {
    String tokenHash = RefreshTokenStore.hashToken(rawRefreshToken);
    // Kill this device's access token immediately too, not just its ability to refresh.
    refreshTokenStore
        .find(tokenHash)
        .ifPresent(d -> refreshTokenStore.denyFamilyAccess(d.familyId(), accessTtlSeconds()));
    refreshTokenStore.revoke(tokenHash);
  }

  public void logoutAll(UUID userId) {
    int newVersion = userRepo.incrementTokenVersion(userId);
    propagateLogoutAll(userId, newVersion);
    log.info("Logged out all sessions for user id={}, new token_version={}", userId, newVersion);
  }

  /**
   * Propagate a logout-all whose {@code token_version} bump has *already* been committed to the DB
   * (typically inside a de-privilege transaction). Caches the new version and revokes every refresh
   * family. If Redis cannot be trusted, drops the cached version instead so the filter's cache-miss
   * path rejects outstanding tokens - fail closed, never fail open, so a demoted user's live token
   * cannot outlive the change.
   */
  public void propagateLogoutAll(UUID userId, int newTokenVersion) {
    try {
      refreshTokenStore.cacheTokenVersion(userId, newTokenVersion);
      refreshTokenStore.revokeAllForUser(userId);
    } catch (RuntimeException e) {
      log.warn(
          "logout-all cache write failed for user id={}; dropping cached token_version to fail"
              + " closed",
          userId,
          e);
      refreshTokenStore.invalidateTokenVersion(userId);
    }
  }

  public List<RefreshTokenStore.SessionInfo> listSessions(UUID userId) {
    return refreshTokenStore.listSessions(userId);
  }

  /** O(1) count of a user's active session families — for callers that need only the number. */
  public long countSessions(UUID userId) {
    return refreshTokenStore.countSessions(userId);
  }

  public void revokeSession(UUID userId, UUID familyId) {
    boolean revoked = refreshTokenStore.revokeFamily(familyId, userId);
    if (!revoked) {
      throw new com.loai.inventory.common.exception.NotFoundException(
          "Session not found: " + familyId);
    }
    // Per-device access-token kill-switch: this device's outstanding access token dies on its next
    // request, not just when it can no longer refresh.
    refreshTokenStore.denyFamilyAccess(familyId, accessTtlSeconds());
  }

  public boolean isTokenVersionValid(UUID userId, int claimedVersion) {
    Optional<Integer> cached = refreshTokenStore.getCachedTokenVersion(userId);
    return cached.isPresent() && cached.get() == claimedVersion;
  }

  /** Filter passthrough for the per-device access-token kill-switch. */
  public boolean isDeviceRevoked(UUID familyId) {
    return refreshTokenStore.isFamilyAccessRevoked(familyId);
  }

  private long accessTtlSeconds() {
    return jwtUtil.getAccessTtlMillis() / 1000;
  }

  /**
   * Start an impersonation overlay. Mints an access token only (no refresh token) whose {@code sub}
   * is the target and whose {@code act} claim is the caller. See {@code docs/impersonation.md} and
   * {@code stories/impersonate_user.md} for the guard rails enforced here.
   */
  public ImpersonationResult impersonate(
      SecurityContext caller,
      UUID targetId,
      ImpersonationTier tier,
      UUID scopeOrgId,
      String reason,
      Environment env) {

    if (caller.isImpersonating()) {
      throw new ConflictException("Already impersonating; stop the current session first");
    }
    UUID callerId = caller.actorId();
    if (callerId.equals(targetId)) {
      throw new ValidationException("Cannot impersonate yourself");
    }

    boolean readOnly = false;
    if (tier == ImpersonationTier.PLATFORM) {
      if (scopeOrgId != null) {
        throw new ValidationException("Platform impersonation is not org-scoped");
      }
      boolean admin = caller.hasSystemRole(SystemRole.ADMIN);
      boolean support = caller.hasSystemRole(SystemRole.SUPPORT);
      if (!admin && !support) {
        throw new AuthorizationException("Requires platform ADMIN or SUPPORT role");
      }
      readOnly = support && !admin; // SUPPORT gets view-as; ADMIN gets full write.
    } else {
      if (scopeOrgId == null) {
        throw new ValidationException("Org impersonation requires an org id");
      }
      // Caller's REAL OWNER role in the scope org — the system-admin bypass is not honored here.
      Set<OrgRole> callerRoles =
          caller.orgRoles() == null ? null : caller.orgRoles().get(scopeOrgId);
      if (callerRoles == null || !callerRoles.contains(OrgRole.OWNER)) {
        throw new AuthorizationException("Requires OWNER role in org " + scopeOrgId);
      }
    }

    AppUser target =
        userRepo.findById(targetId).orElseThrow(() -> new NotFoundException("User", targetId));
    if (!target.isActive()) {
      throw new AuthorizationException("Target account is disabled");
    }

    Map<UUID, Set<String>> overlayOrgRoles;
    if (tier == ImpersonationTier.PLATFORM) {
      if (userRepo.findSystemRoles(targetId).contains(SystemRole.ADMIN)) {
        throw new AuthorizationException("Cannot impersonate a system admin");
      }
      overlayOrgRoles = buildOrgRolesMap(userRepo.findOrgRoles(targetId));
    } else {
      // Scope the overlay to the one org — never leak the target's access to their other orgs.
      Set<String> rolesInScope = new HashSet<>();
      for (UserOrgRole r : userRepo.findOrgRoles(targetId)) {
        if (r.getOrgId().equals(scopeOrgId)) {
          rolesInScope.add(r.getRole().name());
        }
      }
      if (rolesInScope.isEmpty()) {
        throw new NotFoundException("Target is not a member of org " + scopeOrgId);
      }
      if (rolesInScope.contains(OrgRole.OWNER.name())) {
        throw new AuthorizationException("Cannot impersonate an OWNER of org " + scopeOrgId);
      }
      overlayOrgRoles = Map.of(scopeOrgId, rolesInScope);
    }

    int targetTokenVersion = userRepo.getTokenVersion(targetId);
    // The overlay's sub is the target, so JwtAuthFilter validates the token against the TARGET's
    // cached token_version. A target who is not currently logged in has no cached version, and the
    // filter treats a cache miss as revoked — so without priming, every overlay request 401s. This
    // also preserves revocation: the target's logout-all re-caches a higher version, killing it.
    if (refreshTokenStore != null) {
      refreshTokenStore.cacheTokenVersion(targetId, targetTokenVersion);
    }
    String token =
        jwtUtil.generateAccessToken(
            target.getId(),
            target.getActorType().name(),
            overlayOrgRoles,
            null, // an overlay never carries system_roles
            Set.of(),
            targetTokenVersion,
            null, // device-less: an overlay carries no fam claim
            callerId,
            tier.name(),
            tier == ImpersonationTier.ORG ? scopeOrgId : null,
            readOnly ? "READONLY" : null,
            impersonationTtlMillis);

    impersonationEventRepo.insert(
        ImpersonationEvent.start(
            callerId,
            targetId,
            tier,
            tier == ImpersonationTier.ORG ? scopeOrgId : null,
            reason,
            env));

    log.info(
        "Impersonation START tier={} driver={} target={} readOnly={}",
        tier,
        callerId,
        targetId,
        readOnly);
    return new ImpersonationResult(token, impersonationTtlMillis / 1000, callerId, tier, readOnly);
  }

  /**
   * End an overlay by re-minting the real driver's own access token (no {@code act} claim). The
   * driver's refresh session was never touched, so this is purely additive; letting the short
   * overlay token expire also works.
   */
  public ImpersonationResult stopImpersonating(SecurityContext caller, Environment env) {
    if (!caller.isImpersonating()) {
      throw new ValidationException("Not impersonating");
    }
    UUID impersonatorId = caller.impersonatorId();
    UUID targetId = caller.actorId();
    ImpersonationTier tier = caller.impersonationTier();
    UUID scopeOrgId = caller.impersonationScopeOrg();

    AppUser driver =
        userRepo
            .findById(impersonatorId)
            .orElseThrow(() -> new AuthenticationException("Impersonator no longer exists"));
    if (!driver.isActive()) {
      throw new AuthorizationException("Impersonator account is disabled");
    }

    // The re-minted token reflects the driver's CURRENT roles, so no further role re-check is
    // needed.
    String token = mintAccessToken(driver);
    impersonationEventRepo.insert(
        ImpersonationEvent.stop(impersonatorId, targetId, tier, scopeOrgId, env));

    log.info("Impersonation STOP tier={} driver={} target={}", tier, impersonatorId, targetId);
    return new ImpersonationResult(token, jwtUtil.getAccessTtlMillis() / 1000, null, tier, false);
  }

  /** Mint a normal (non-overlay) access token for a user, reflecting their current roles. */
  private String mintAccessToken(AppUser user) {
    Map<UUID, Set<String>> orgRoles = buildOrgRolesMap(userRepo.findOrgRoles(user.getId()));
    Set<String> systemRoles = buildSystemRolesSet(userRepo.findSystemRoles(user.getId()));
    int tokenVersion = userRepo.getTokenVersion(user.getId());
    return jwtUtil.generateAccessToken(
        user.getId(), user.getActorType().name(), orgRoles, systemRoles, Set.of(), tokenVersion);
  }

  private Map<UUID, Set<String>> buildOrgRolesMap(List<UserOrgRole> roles) {
    Map<UUID, Set<String>> map = new HashMap<>();
    for (UserOrgRole r : roles) {
      map.computeIfAbsent(r.getOrgId(), k -> new HashSet<>()).add(r.getRole().name());
    }
    return map;
  }

  private Set<String> buildSystemRolesSet(Set<SystemRole> roles) {
    return roles.stream().map(SystemRole::name).collect(Collectors.toSet());
  }
}
