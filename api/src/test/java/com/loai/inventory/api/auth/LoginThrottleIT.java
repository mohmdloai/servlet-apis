package com.loai.inventory.api.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.TooManyAttemptsException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.LoginThrottle;
import com.loai.inventory.service.auth.RefreshTokenStore;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * D9 — login hardening. The per-IP fixed window never sees a distributed attack on one account, and
 * an unknown address used to answer measurably faster than a known one. These pin the per-account
 * lockout, the fact that it is keyed on the address (so it cannot answer "does this account
 * exist"), and that every counter key carries a TTL from the moment it is created.
 */
@Testcontainers
class LoginThrottleIT {

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  static JedisPool pool;
  static LoginThrottle throttle;

  private static final String PASSWORD = "correct horse battery";

  @BeforeAll
  static void setup() {
    pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    throttle = new LoginThrottle(pool);
  }

  @Test
  @DisplayName("repeated failures lock the account, whatever IP they come from")
  void repeatedFailuresLockTheAccount() {
    String email = unique();
    AuthService auth = serviceFor(email);

    // Ten wrong passwords, each from a different source IP — the per-IP bucket would never notice.
    for (int i = 0; i < 10; i++) {
      final int n = i;
      assertThrows(
          AuthenticationException.class,
          () -> auth.login(email, "wrong-" + n, "ua", "10.0.0." + n),
          "attempt " + n + " must fail as a bad credential, not a lockout");
    }

    // The eleventh is refused before any credential check — and the *right* password is refused
    // too.
    assertThrows(
        TooManyAttemptsException.class, () -> auth.login(email, PASSWORD, "ua", "10.0.0.99"));
  }

  @Test
  @DisplayName("a successful login clears the budget")
  void successClearsTheCounter() {
    String email = unique();
    AuthService auth = serviceFor(email);

    for (int i = 0; i < 3; i++) {
      final int n = i;
      assertThrows(
          AuthenticationException.class, () -> auth.login(email, "wrong", "ua", "1.1.1." + n));
    }
    assertFalse(throttle.isLocked(email), "three failures are not a lockout");

    auth.login(email, PASSWORD, "ua", "1.1.1.1");

    // The budget really restarted from zero: nine more failures (3 + 9 = 12 > the threshold, had
    // the earlier ones survived) still leave the account open.
    for (int i = 0; i < 9; i++) {
      assertThrows(
          AuthenticationException.class, () -> auth.login(email, "wrong", "ua", "1.1.1.9"));
    }
    assertFalse(throttle.isLocked(email), "a success wipes the earlier failures");
  }

  @Test
  @DisplayName("the counter is keyed on the address, so an unknown one throttles identically")
  void unknownAddressesAreThrottledToo() {
    String known = unique();
    String unknown = unique();
    AuthService auth = serviceFor(known);

    for (int i = 0; i < 10; i++) {
      assertThrows(
          AuthenticationException.class, () -> auth.login(unknown, "guess", "ua", "10.0.0.1"));
    }

    assertTrue(
        throttle.isLocked(unknown),
        "an address with no account must lock exactly like one that has an account — otherwise the"
            + " lockout itself says which addresses are real");
    assertFalse(throttle.isLocked(known), "one account's failures must not touch another's");
  }

  @Test
  @DisplayName("every failure key carries a TTL — a stranded counter would be a permanent lockout")
  void everyCounterKeyHasATtl() {
    String email = unique();
    throttle.recordFailure(email);

    try (Jedis jedis = pool.getResource()) {
      List<String> keys = jedis.keys("auth:fail:*").stream().toList();
      assertFalse(keys.isEmpty(), "the failure was recorded");
      for (String key : keys) {
        assertTrue(jedis.ttl(key) > 0, key + " must expire — a TTL-less counter never unlocks");
      }
    }
  }

  @Test
  @DisplayName("the disabled throttle is inert")
  void disabledThrottleNeverLocks() {
    LoginThrottle off = LoginThrottle.disabled();
    for (int i = 0; i < 50; i++) {
      off.recordFailure("someone@acme.test");
    }
    assertFalse(off.isLocked("someone@acme.test"));
  }

  // helpers

  private static String unique() {
    return "user-" + UUID.randomUUID() + "@acme.test";
  }

  /**
   * An {@link AuthService} over one real, active, verified account with {@link #PASSWORD}. The user
   * repository is mocked — this test is about the throttle, not persistence.
   */
  private static AuthService serviceFor(String email) {
    AppUser user =
        new AppUser(
            UUID.randomUUID(),
            email,
            PasswordHasher.hash(PASSWORD),
            ActorType.USER,
            true,
            1,
            OffsetDateTime.now(),
            OffsetDateTime.now());
    user.setEmailVerifiedAt(OffsetDateTime.now());
    UserRepository repo = mock(UserRepository.class);
    when(repo.findByEmail(email)).thenReturn(Optional.of(user));
    when(repo.findById(user.getId())).thenReturn(Optional.of(user));
    when(repo.findOrgRoles(user.getId())).thenReturn(List.of());
    when(repo.findSystemRoles(user.getId())).thenReturn(Set.of());
    when(repo.getTokenVersion(user.getId())).thenReturn(1);
    return new AuthService(
        repo,
        new RefreshTokenStore(pool),
        new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L),
        null,
        300_000L,
        throttle);
  }
}
