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
 * matching the {@code token_version} cache pattern. Suspend/reactivate write the new value straight
 * into the cache, so enforcement takes effect immediately with no invalidation window.
 */
public class OrgStatusService {

  private static final String KEY_PREFIX = "org:active:";
  // Short TTL: the cache is authoritative right after a toggle (we write through), and self-heals
  // from the DB on expiry, so a stale entry can never outlive the TTL even if a write is missed.
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

  /** Write the active flag through to the cache so a suspend/reactivate takes effect at once. */
  public void set(UUID orgId, boolean active) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.setex(KEY_PREFIX + orgId, TTL_SECONDS, active ? "1" : "0");
    }
  }
}
