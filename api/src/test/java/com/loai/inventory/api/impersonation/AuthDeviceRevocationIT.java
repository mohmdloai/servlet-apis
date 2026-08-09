package com.loai.inventory.api.impersonation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * Per-device access-token kill-switch. A single-device revoke ({@code DELETE /sessions/{familyId}})
 * and a current-device {@code logout} must denylist that family so the filter rejects its
 * outstanding access token immediately (not only block future refreshes). Exercises the
 * Redis-backed mechanism directly — no Postgres needed, since these paths never touch the user
 * repository.
 */
@Testcontainers
class AuthDeviceRevocationIT {

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  static RefreshTokenStore store;
  static AuthService authService;

  @BeforeAll
  static void setup() {
    store = new RefreshTokenStore(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)));
    JwtUtil jwt = new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L);
    authService = new AuthService(null, store, jwt, null, 300_000L);
  }

  private UUID seedFamily(UUID userId, String rawRefresh) {
    UUID familyId = UUID.randomUUID();
    store.store(
        RefreshTokenStore.hashToken(rawRefresh),
        new RefreshTokenStore.TokenData(userId, familyId, "device", "1.2.3.4", Instant.now()));
    return familyId;
  }

  @Test
  void revokeSession_denylistsThatDeviceImmediately() {
    UUID user = UUID.randomUUID();
    String raw = UUID.randomUUID().toString();
    UUID family = seedFamily(user, raw);
    assertFalse(authService.isDeviceRevoked(family));

    authService.revokeSession(user, family);

    assertTrue(authService.isDeviceRevoked(family), "the device's access token must be killed");
    assertTrue(store.find(RefreshTokenStore.hashToken(raw)).isEmpty(), "refresh also gone");
  }

  @Test
  void logout_denylistsCurrentDevice() {
    UUID user = UUID.randomUUID();
    String raw = UUID.randomUUID().toString();
    UUID family = seedFamily(user, raw);

    authService.logout(raw);

    assertTrue(authService.isDeviceRevoked(family), "logout kills this device's access token");
  }

  @Test
  void otherDeviceIsUnaffected() {
    UUID user = UUID.randomUUID();
    UUID keep = seedFamily(user, UUID.randomUUID().toString());
    UUID kill = seedFamily(user, UUID.randomUUID().toString());

    authService.revokeSession(user, kill);

    assertTrue(authService.isDeviceRevoked(kill));
    assertFalse(authService.isDeviceRevoked(keep), "revoking one device must not touch the other");
  }

  @Test
  void revokeSession_foreignFamily_throwsNotFound() {
    UUID user = UUID.randomUUID();
    // Family belongs to nobody in the user's set → cannot be revoked.
    assertThrows(NotFoundException.class, () -> authService.revokeSession(user, UUID.randomUUID()));
  }

  // D2: reuse of a rotated-away refresh token burns the family

  /**
   * The theft ordering that mattered: the thief refreshes first (T1 → T2), so it is the *victim*
   * who later presents the stale T1. That presentation must not merely 401 — it is proof two
   * holders exist, and the family (T2 included) has to die.
   */
  @Test
  void reuseOfRotatedToken_revokesTheWholeFamily() {
    UUID user = UUID.randomUUID();
    AuthService svc = serviceFor(user);

    String t1 = UUID.randomUUID().toString();
    UUID family = seedFamily(user, t1);

    // The thief refreshes: T1 rotates away, T2 is minted in the same family.
    AuthService.LoginResult rotated = svc.refresh(t1, "9.9.9.9");
    String t2 = rotated.refreshToken();
    assertTrue(
        store.find(RefreshTokenStore.hashToken(t2)).isPresent(), "T2 is live after rotation");

    // The victim presents T1 — 401, and now T2 must be dead too.
    assertThrows(AuthenticationException.class, () -> svc.refresh(t1, "1.2.3.4"));
    assertTrue(
        store.find(RefreshTokenStore.hashToken(t2)).isEmpty(),
        "the thief's freshly-minted token must be revoked with the family");
    assertTrue(
        svc.isDeviceRevoked(family),
        "outstanding access tokens of that family must be killed too, not just its refreshes");
  }

  /** An unknown token is not evidence of anything — it 401s and revokes nothing. */
  @Test
  void unknownToken_401sButRevokesNothing() {
    UUID user = UUID.randomUUID();
    AuthService svc = serviceFor(user);
    UUID family = seedFamily(user, UUID.randomUUID().toString());

    assertThrows(
        AuthenticationException.class, () -> svc.refresh(UUID.randomUUID().toString(), "1.2.3.4"));

    assertFalse(svc.isDeviceRevoked(family), "a garbage token must not kill a live session");
  }

  /**
   * An {@link AuthService} whose user lookups resolve to one active user — enough for {@code
   * refresh} to mint, without a Postgres container (these paths only read identity + roles).
   */
  private static AuthService serviceFor(UUID userId) {
    AppUser user =
        new AppUser(
            userId,
            "owner@acme.test",
            "irrelevant",
            ActorType.USER,
            true,
            1,
            OffsetDateTime.now(),
            OffsetDateTime.now());
    UserRepository repo = mock(UserRepository.class);
    when(repo.findById(userId)).thenReturn(Optional.of(user));
    when(repo.findOrgRoles(userId)).thenReturn(List.of());
    when(repo.findSystemRoles(userId)).thenReturn(Set.of());
    when(repo.getTokenVersion(userId)).thenReturn(1);
    JwtUtil jwt = new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L);
    return new AuthService(repo, store, jwt, null, 300_000L);
  }
}
