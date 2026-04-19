package com.loai.inventory.service.auth;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.UserOrgRole;
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

  private final UserRepository userRepo;
  private final RefreshTokenStore refreshTokenStore;
  private final JwtUtil jwtUtil;

  public AuthService(
      UserRepository userRepo, RefreshTokenStore refreshTokenStore, JwtUtil jwtUtil) {
    this.userRepo = userRepo;
    this.refreshTokenStore = refreshTokenStore;
    this.jwtUtil = jwtUtil;
  }

  public record LoginResult(
      String accessToken, String refreshToken, long expiresIn, AppUser user) {}

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

    String accessToken =
        jwtUtil.generateAccessToken(
            user.getId(),
            user.getActorType().name(),
            orgRoles,
            systemRoles,
            Set.of(),
            tokenVersion);

    UUID familyId = UUID.randomUUID();
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
            tokenVersion);

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
    refreshTokenStore.revoke(tokenHash);
  }

  public void logoutAll(UUID userId) {
    int newVersion = userRepo.incrementTokenVersion(userId);
    refreshTokenStore.cacheTokenVersion(userId, newVersion);
    refreshTokenStore.revokeAllForUser(userId);
    log.info("Logged out all sessions for user id={}, new token_version={}", userId, newVersion);
  }

  public List<RefreshTokenStore.SessionInfo> listSessions(UUID userId) {
    return refreshTokenStore.listSessions(userId);
  }

  public void revokeSession(UUID userId, UUID familyId) {
    boolean revoked = refreshTokenStore.revokeFamily(familyId, userId);
    if (!revoked) {
      throw new com.loai.inventory.common.exception.NotFoundException(
          "Session not found: " + familyId);
    }
  }

  public boolean isTokenVersionValid(UUID userId, int claimedVersion) {
    Optional<Integer> cached = refreshTokenStore.getCachedTokenVersion(userId);
    return cached.isPresent() && cached.get() == claimedVersion;
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
