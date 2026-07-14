package com.loai.inventory.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * The passwordless-login OTP challenge, held entirely in Redis (self-expiring — no DB table, no
 * cleanup cron). One challenge per {@code (orgId, sha256(email))} at key {@code
 * portal:otp:{orgId}:{emailHash}} → JSON {@code {codeHash, attempts, expiresAt}} with a 10-minute
 * TTL. The code is a 6-digit {@link SecureRandom} value; only its SHA-256 hash is stored (reusing
 * {@link RefreshTokenStore#hashToken}), compared in constant time. A challenge is single-use
 * (deleted on match) and dies after 5 wrong attempts (the 6th invalidates it — the caller must
 * re-request). See {@code stories/portal_auth_core.md} §"OTP challenge" and the epic's decision #7.
 *
 * <p>A per-email <em>send</em> throttle ({@link #allowSend}) guards a single inbox from OTP-spam /
 * enumeration-DoS independently of the per-IP {@code RateLimitFilter} bucket. It is advisory: when
 * exceeded the caller simply skips the send while still returning the uniform {@code {sent:true}},
 * so it never becomes an account-existence oracle.
 */
public class CustomerOtpStore {

  private static final Logger log = LoggerFactory.getLogger(CustomerOtpStore.class);

  /** 10-minute challenge lifetime (epic decision #7). */
  static final long CHALLENGE_TTL_SECONDS = 10 * 60;

  /** Wrong-attempt cap: the challenge dies once attempts exceed this (the 6th try invalidates). */
  static final int MAX_ATTEMPTS = 5;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final JedisPool jedisPool;
  private final ObjectMapper objectMapper;

  public CustomerOtpStore(JedisPool jedisPool) {
    this.jedisPool = jedisPool;
    this.objectMapper = new ObjectMapper();
  }

  /** The persisted challenge (JSON in Redis). {@code expiresAt} is epoch millis (fixed window). */
  record Challenge(String codeHash, int attempts, long expiresAt) {}

  /**
   * The outcome of a {@link #verify} — deliberately coarse so the caller emits one generic error.
   */
  public enum VerifyResult {
    MATCH,
    FAIL
  }

  private static String key(java.util.UUID orgId, String emailHash) {
    return "portal:otp:" + orgId + ":" + emailHash;
  }

  static String emailHash(String normalizedEmail) {
    return RefreshTokenStore.hashToken(normalizedEmail);
  }

  /** Generate a fresh 6-digit code, store its hash under a 10-min TTL, and return the raw code. */
  public String issueCode(java.util.UUID orgId, String normalizedEmail, long nowMillis) {
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    Challenge challenge =
        new Challenge(
            RefreshTokenStore.hashToken(code), 0, nowMillis + CHALLENGE_TTL_SECONDS * 1000);
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.setex(
          key(orgId, emailHash(normalizedEmail)),
          CHALLENGE_TTL_SECONDS,
          objectMapper.writeValueAsString(challenge));
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize OTP challenge", e);
    }
    return code;
  }

  /**
   * Verify a presented code against the live challenge for {@code (orgId, email)}. Missing /
   * expired / exhausted / wrong all yield {@link VerifyResult#FAIL} (no distinguishing signal). A
   * match deletes the challenge (single-use) and returns {@link VerifyResult#MATCH}.
   */
  public VerifyResult verify(
      java.util.UUID orgId, String normalizedEmail, String code, long nowMillis) {
    String k = key(orgId, emailHash(normalizedEmail));
    try (Jedis jedis = jedisPool.getResource()) {
      String json = jedis.get(k);
      if (json == null) {
        return VerifyResult.FAIL; // never requested, or already expired/consumed
      }
      Challenge challenge;
      try {
        challenge = objectMapper.readValue(json, Challenge.class);
      } catch (JsonProcessingException e) {
        log.warn("Corrupt OTP challenge for org {} — dropping", orgId);
        jedis.del(k);
        return VerifyResult.FAIL;
      }
      if (nowMillis >= challenge.expiresAt()) {
        jedis.del(k);
        return VerifyResult.FAIL;
      }
      int attempts = challenge.attempts() + 1;
      if (attempts > MAX_ATTEMPTS) {
        jedis.del(k); // brute-force cap tripped — must re-request
        return VerifyResult.FAIL;
      }
      boolean matches =
          code != null
              && constantTimeEquals(RefreshTokenStore.hashToken(code), challenge.codeHash());
      if (matches) {
        jedis.del(k); // single-use
        return VerifyResult.MATCH;
      }
      // Wrong code: persist the incremented attempt count within the SAME fixed window (recompute
      // the remaining TTL from expiresAt, so retries never extend the challenge's lifetime).
      long remainingSeconds = Math.max(1, (challenge.expiresAt() - nowMillis) / 1000);
      try {
        jedis.setex(
            k,
            remainingSeconds,
            objectMapper.writeValueAsString(
                new Challenge(challenge.codeHash(), attempts, challenge.expiresAt())));
      } catch (JsonProcessingException e) {
        throw new RuntimeException("Failed to serialize OTP challenge", e);
      }
      return VerifyResult.FAIL;
    }
  }

  /**
   * Per-email send throttle: {@code true} while the {@code (orgId, email)} inbox is under {@code
   * limit} sends per {@code windowSeconds}. Advisory (see the class note) — a {@code false}
   * suppresses the send without changing the uniform response.
   */
  public boolean allowSend(
      java.util.UUID orgId, String normalizedEmail, int limit, int windowSeconds) {
    String k = "portal:otp:send:" + orgId + ":" + emailHash(normalizedEmail);
    try (Jedis jedis = jedisPool.getResource()) {
      long current = jedis.incr(k);
      if (current == 1) {
        jedis.expire(k, windowSeconds);
      }
      return current <= limit;
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
