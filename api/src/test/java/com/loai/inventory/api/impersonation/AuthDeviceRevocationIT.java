package com.loai.inventory.api.impersonation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import java.time.Instant;
import java.util.Base64;
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
}
