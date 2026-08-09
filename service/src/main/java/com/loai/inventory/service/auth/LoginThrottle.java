package com.loai.inventory.service.auth;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * A per-<b>account</b> failed-login counter with a short lockout, independent of the per-IP bucket
 * in {@code RateLimitFilter}.
 *
 * <p>Per-IP alone throttles nobody who can rotate IPs: a distributed brute-force against one
 * account, or credential-stuffing a leaked list across many accounts, never fills a single bucket.
 * Keying on the account is the other half — the two together bound both "many guesses at one door"
 * and "one guess at many doors".
 *
 * <p><b>Keyed on the presented email, not a user id</b>, deliberately: the counter must behave
 * identically for an address that has no account, or the lockout itself becomes the enumeration
 * oracle the timing fix just closed.
 *
 * <p><b>The trade-off, stated:</b> an attacker who knows an address can deliberately fail {@value
 * #MAX_FAILURES} times and lock its owner out for {@value #LOCKOUT_SECONDS} seconds. That is why
 * the window is minutes rather than hours and the threshold is generous enough that a person
 * mistyping a password never reaches it — a brief self-clearing lockout is a far smaller harm than
 * an unthrottled password oracle, and a successful login clears the counter outright.
 */
public class LoginThrottle {

  /** Consecutive failures on one account before it is locked. */
  static final int MAX_FAILURES = 10;

  /** How long a locked account stays locked, and how long the failure count itself lives. */
  static final int LOCKOUT_SECONDS = 15 * 60;

  private final JedisPool jedisPool;

  public LoginThrottle(JedisPool jedisPool) {
    this.jedisPool = jedisPool;
  }

  /**
   * A no-op throttle, for callers that have no Redis (unit tests, and code paths that construct an
   * {@link AuthService} purely to reach its token machinery). Explicit rather than a nullable
   * collaborator, so no production branch has to ask whether throttling is on.
   */
  public static LoginThrottle disabled() {
    return new LoginThrottle(null) {
      @Override
      public boolean isLocked(String email) {
        return false;
      }

      @Override
      public void recordFailure(String email) {}

      @Override
      public void clear(String email) {}
    };
  }

  /** True when this account has spent its failure budget and is inside the lockout window. */
  public boolean isLocked(String email) {
    if (email == null) {
      return false;
    }
    try (Jedis jedis = jedisPool.getResource()) {
      String v = jedis.get(key(email));
      return v != null && parse(v) >= MAX_FAILURES;
    }
  }

  /**
   * Count one failed attempt. The TTL is written <em>before</em> the increment (via {@code SET NX
   * EX}), so the key can never be left without one — the reverse order strands a counter forever if
   * the process dies between the two commands, and a stranded counter locks that account out
   * permanently.
   */
  public void recordFailure(String email) {
    if (email == null) {
      return;
    }
    try (Jedis jedis = jedisPool.getResource()) {
      String k = key(email);
      jedis.set(k, "0", SetParams.setParams().nx().ex(LOCKOUT_SECONDS));
      jedis.incr(k);
    }
  }

  /** Forget this account's failures — called on a successful authentication. */
  public void clear(String email) {
    if (email == null) {
      return;
    }
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(key(email));
    }
  }

  /**
   * The bucket key. Hashed so a Redis dump is not a list of the addresses people have been trying
   * to log in as; the same hash the token store uses, over a fixed-prefix input.
   */
  private static String key(String email) {
    return "auth:fail:" + RefreshTokenStore.hashToken("login:" + email);
  }

  private static long parse(String v) {
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
