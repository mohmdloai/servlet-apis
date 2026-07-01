package com.loai.inventory.service.platform;

import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import java.util.UUID;
import org.jooq.DSLContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Answers "is this org active?" for the authorization hot path (see {@code
 * docs/platform-admin-plan.md}, slice 5). Every org-scoped request consults this, so the answer is
 * cached in Redis (a mirror of {@code org.active}) rather than hitting Postgres each time -
 * matching the {@code token_version} cache pattern. Suspend/reactivate *invalidate* the entry (they
 * do not write a value): the next read then load-through's the freshly-committed DB row and
 * re-caches it. Invalidating rather than writing avoids leaving a stale value behind if the toggle
 * and the cache update race - a missing key is always re-derived from the source of truth.
 */
public class OrgStatusService {

  private static final String KEY_PREFIX = "org:active:";
  // Short TTL: on a cache miss we load-through from the DB, so a stale entry can never outlive the
  // TTL even if an invalidation is somehow missed.
  private static final long TTL_SECONDS = 300;

  private final JedisPool jedisPool;
  private final DSLContext dsl;
  private final OrgRepositoryFactory orgRepoFactory;

  public OrgStatusService(
      JedisPool jedisPool, DSLContext dsl, OrgRepositoryFactory orgRepoFactory) {
    this.jedisPool = jedisPool;
    this.dsl = dsl;
    this.orgRepoFactory = orgRepoFactory;
  }

  /** True if the org is active (not suspended). Unknown orgs are treated as active. */
  public boolean isActive(UUID orgId) {
    try (Jedis jedis = jedisPool.getResource()) {
      String cached = jedis.get(KEY_PREFIX + orgId);
      if (cached != null) {
        return "1".equals(cached);
      }
      boolean active = orgRepoFactory.create(dsl).findById(orgId).map(Org::isActive).orElse(true);
      jedis.setex(KEY_PREFIX + orgId, TTL_SECONDS, active ? "1" : "0");
      return active;
    }
  }

  /**
   * Invalidate the cached flag after a suspend/reactivate has committed. The next {@link #isActive}
   * load-through's the new DB value, so enforcement flips on the following request with no risk of
   * a stale write lingering for the TTL.
   */
  public void invalidate(UUID orgId) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(KEY_PREFIX + orgId);
    }
  }
}
