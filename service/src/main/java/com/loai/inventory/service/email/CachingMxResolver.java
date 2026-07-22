package com.loai.inventory.service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Redis verdict cache in front of a real {@link MxResolver} — the same domains (gmail.com,
 * outlook.com, …) recur constantly, and a DNS round-trip per checkout would be waste. Keys {@code
 * email:mx:{domain}}; positive verdicts live longer than negative ones (a domain fixing its DNS
 * should be seen within minutes), and {@code UNKNOWN} is <b>never</b> cached — a transient resolver
 * blip must not stick. Any Redis failure degrades to an uncached live lookup (fail-open).
 */
public class CachingMxResolver implements MxResolver {

  private static final Logger log = LoggerFactory.getLogger(CachingMxResolver.class);

  static final String KEY_PREFIX = "email:mx:";
  static final long DELIVERABLE_TTL_SECONDS = 3600;
  static final long UNDELIVERABLE_TTL_SECONDS = 300;

  private final MxResolver delegate;
  private final JedisPool jedisPool;

  public CachingMxResolver(MxResolver delegate, JedisPool jedisPool) {
    this.delegate = delegate;
    this.jedisPool = jedisPool;
  }

  @Override
  public MxResult lookup(String domain) {
    String key = KEY_PREFIX + domain;
    try (Jedis jedis = jedisPool.getResource()) {
      String cached = jedis.get(key);
      if (cached != null) {
        try {
          return MxResult.valueOf(cached);
        } catch (IllegalArgumentException e) {
          // A corrupt/foreign value — fall through to a live lookup and overwrite.
        }
      }
      MxResult result = delegate.lookup(domain);
      if (result == MxResult.DELIVERABLE) {
        jedis.set(key, result.name(), new SetParams().ex(DELIVERABLE_TTL_SECONDS));
      } else if (result == MxResult.UNDELIVERABLE) {
        jedis.set(key, result.name(), new SetParams().ex(UNDELIVERABLE_TTL_SECONDS));
      }
      return result;
    } catch (RuntimeException e) {
      // Redis down → the gate still answers; just uncached.
      log.warn("MX verdict cache unavailable — falling back to a live lookup", e);
      return delegate.lookup(domain);
    }
  }
}
