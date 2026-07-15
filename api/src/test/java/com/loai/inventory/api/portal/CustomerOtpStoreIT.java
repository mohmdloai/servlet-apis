package com.loai.inventory.api.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.service.auth.CustomerOtpStore;
import com.loai.inventory.service.auth.CustomerOtpStore.VerifyResult;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * Unit-level checks of the Redis OTP challenge (AC3): a match consumes it (single-use), the wrong
 * code fails, the 6th attempt invalidates it, an expired challenge fails, and the per-email send
 * throttle counts down. A fixed injected {@code now} lets us test the 10-minute window without
 * sleeping.
 */
@Testcontainers
class CustomerOtpStoreIT {

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  static JedisPool jedisPool;
  CustomerOtpStore store;
  final UUID org = UUID.randomUUID();
  final String email = "nadia@acme.test";
  final long now = 1_000_000_000_000L;

  @BeforeEach
  void setUp() {
    if (jedisPool == null) {
      jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    }
    try (var jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }
    store = new CustomerOtpStore(jedisPool);
  }

  @Test
  void correctCodeMatchesOnce_thenIsConsumed() {
    String code = store.issueCode(org, email, now);
    assertEquals(VerifyResult.MATCH, store.verify(org, email, code, now));
    assertEquals(
        VerifyResult.FAIL, store.verify(org, email, code, now), "single-use: gone after a match");
  }

  @Test
  void wrongCodeFails_andNeverMatches() {
    store.issueCode(org, email, now);
    assertEquals(VerifyResult.FAIL, store.verify(org, email, "000000", now));
  }

  @Test
  void sixthAttemptInvalidates_evenIfCorrect() {
    String code = store.issueCode(org, email, now);
    for (int i = 0; i < 5; i++) {
      assertEquals(VerifyResult.FAIL, store.verify(org, email, "111111", now), "wrong " + i);
    }
    assertEquals(
        VerifyResult.FAIL,
        store.verify(org, email, code, now),
        "6th attempt is rejected and drops the challenge");
    assertEquals(VerifyResult.FAIL, store.verify(org, email, code, now), "challenge is gone");
  }

  @Test
  void expiredChallengeFails() {
    String code = store.issueCode(org, email, now);
    long afterTtl = now + 11 * 60 * 1000; // > 10-minute window
    assertEquals(VerifyResult.FAIL, store.verify(org, email, code, afterTtl));
  }

  @Test
  void missingChallengeFails() {
    assertEquals(VerifyResult.FAIL, store.verify(org, email, "123456", now));
  }

  @Test
  void perEmailSendThrottleCountsDown() {
    assertTrue(store.allowSend(org, email, 2, 60));
    assertTrue(store.allowSend(org, email, 2, 60));
    assertFalse(store.allowSend(org, email, 2, 60), "third send in the window is throttled");
  }
}
