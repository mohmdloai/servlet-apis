package com.loai.inventory.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * The customer-portal session store — a second, isolated copy of {@link RefreshTokenStore} under
 * its own Redis namespace ({@code crt:*} / {@code cust:ver:*}), keyed by {@code (orgId,
 * customerId)} rather than a single user id. It inherits every property of the staff store:
 * refresh-family rotation, reuse-theft revoke, per-device access kill-switch, and a fail-closed
 * {@code token_version} (a cache miss is treated as revoked, so the version must be primed at
 * verify-time). Nothing is shared with the staff store — a leaked customer session cannot touch the
 * staff plane. See {@code stories/portal_auth_core.md} §Sessions + the epic's decision #5.
 */
public class CustomerSessionStore {

  private static final Logger log = LoggerFactory.getLogger(CustomerSessionStore.class);

  private final JedisPool jedisPool;
  private final ObjectMapper objectMapper;
  private final long tokenTtlSeconds;
  private final long familyTtlSeconds;

  public CustomerSessionStore(JedisPool jedisPool, long tokenTtlSeconds) {
    this.jedisPool = jedisPool;
    this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    this.tokenTtlSeconds = tokenTtlSeconds;
    this.familyTtlSeconds = tokenTtlSeconds + 24 * 3600; // outlive the tokens it tracks
  }

  public record TokenData(
      UUID orgId,
      UUID customerId,
      UUID familyId,
      String deviceInfo,
      String sourceIp,
      Instant issuedAt) {}

  public record SessionInfo(
      UUID familyId, String deviceInfo, String sourceIp, Instant lastIssuedAt) {}

  /**
   * SHA-256 of a raw refresh token — reuses the staff store's hasher (a token is stored by hash).
   */
  public static String hashRefresh(String rawToken) {
    return RefreshTokenStore.hashToken(rawToken);
  }

  private static String tokenKey(String hash) {
    return "crt:" + hash;
  }

  private static String familyKey(UUID familyId) {
    return "crt:fam:" + familyId;
  }

  private static String userKey(UUID orgId, UUID customerId) {
    return "crt:user:" + orgId + ":" + customerId;
  }

  private static String verKey(UUID orgId, UUID customerId) {
    return "cust:ver:" + orgId + ":" + customerId;
  }

  public void store(String tokenHash, TokenData data) {
    try (Jedis jedis = jedisPool.getResource()) {
      String json = objectMapper.writeValueAsString(data);
      jedis.setex(tokenKey(tokenHash), tokenTtlSeconds, json);
      String fam = familyKey(data.familyId());
      jedis.sadd(fam, tokenHash);
      jedis.expire(fam, familyTtlSeconds);
      jedis.sadd(userKey(data.orgId(), data.customerId()), data.familyId().toString());
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize customer token data", e);
    }
  }

  public Optional<TokenData> find(String tokenHash) {
    try (Jedis jedis = jedisPool.getResource()) {
      return findWithJedis(jedis, tokenHash);
    }
  }

  public void revoke(String tokenHash) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(tokenKey(tokenHash));
    }
  }

  /** Who a rotated-away refresh token belonged to — the tombstone {@link #rotateAway} leaves. */
  public record RotatedRef(UUID orgId, UUID customerId, UUID familyId) {}

  /**
   * Retire a refresh token because it was just rotated, leaving a tombstone naming its family — the
   * customer-plane copy of {@link RefreshTokenStore#rotateAway}, and for the same reason: a plain
   * {@code DEL} makes a stolen-then-rotated token indistinguishable from garbage on its next
   * presentation, so reuse can be logged but not acted on.
   */
  public void rotateAway(String tokenHash, TokenData data) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(tokenKey(tokenHash));
      jedis.setex(
          "crt:rotated:" + tokenHash,
          tokenTtlSeconds,
          data.orgId() + ":" + data.customerId() + ":" + data.familyId());
    }
  }

  /**
   * The family a presented-but-unknown token was rotated out of, if we recorded one. Empty = an
   * ordinary unknown token; the caller must revoke nothing.
   */
  public Optional<RotatedRef> findRotatedFamily(String tokenHash) {
    String val;
    try (Jedis jedis = jedisPool.getResource()) {
      val = jedis.get("crt:rotated:" + tokenHash);
    }
    if (val == null) {
      return Optional.empty();
    }
    String[] parts = val.split(":", 3);
    if (parts.length != 3) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new RotatedRef(
              UUID.fromString(parts[0]), UUID.fromString(parts[1]), UUID.fromString(parts[2])));
    } catch (IllegalArgumentException e) {
      log.warn("Malformed customer rotation tombstone ignored");
      return Optional.empty();
    }
  }

  /** Per-device access-token kill-switch — denylist a family for the access-token TTL. */
  public void denyFamilyAccess(UUID familyId, long ttlSeconds) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.setex("crt:revoked-fam:" + familyId, ttlSeconds, "1");
    }
  }

  public boolean isFamilyAccessRevoked(UUID familyId) {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.exists("crt:revoked-fam:" + familyId);
    }
  }

  public boolean familyExists(UUID familyId) {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.exists(familyKey(familyId));
    }
  }

  /** Revoke one family (device) — true iff it belonged to this customer. */
  public boolean revokeFamily(UUID familyId, UUID orgId, UUID customerId) {
    String userKey = userKey(orgId, customerId);
    try (Jedis jedis = jedisPool.getResource()) {
      if (!jedis.sismember(userKey, familyId.toString())) {
        return false;
      }
      Set<String> tokenHashes = jedis.smembers(familyKey(familyId));
      if (tokenHashes != null) {
        for (String hash : tokenHashes) {
          jedis.del(tokenKey(hash));
        }
      }
      jedis.del(familyKey(familyId));
      jedis.srem(userKey, familyId.toString());
      return true;
    }
  }

  public void revokeAllForCustomer(UUID orgId, UUID customerId) {
    String userKey = userKey(orgId, customerId);
    try (Jedis jedis = jedisPool.getResource()) {
      Set<String> familyIds = jedis.smembers(userKey);
      if (familyIds != null) {
        for (String fid : familyIds) {
          Set<String> tokenHashes = jedis.smembers("crt:fam:" + fid);
          if (tokenHashes != null) {
            for (String hash : tokenHashes) {
              jedis.del(tokenKey(hash));
            }
          }
          jedis.del("crt:fam:" + fid);
        }
      }
      jedis.del(userKey);
    }
  }

  public long countSessions(UUID orgId, UUID customerId) {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.scard(userKey(orgId, customerId));
    }
  }

  public List<SessionInfo> listSessions(UUID orgId, UUID customerId) {
    String userKey = userKey(orgId, customerId);
    List<SessionInfo> sessions = new ArrayList<>();
    try (Jedis jedis = jedisPool.getResource()) {
      Set<String> familyIds = jedis.smembers(userKey);
      if (familyIds == null) return sessions;
      for (String fid : familyIds) {
        UUID familyId = UUID.fromString(fid);
        Set<String> tokenHashes = jedis.smembers("crt:fam:" + fid);
        if (tokenHashes == null || tokenHashes.isEmpty()) {
          jedis.srem(userKey, fid);
          continue;
        }
        TokenData latest = null;
        for (String hash : tokenHashes) {
          Optional<TokenData> data = findWithJedis(jedis, hash);
          if (data.isPresent()
              && (latest == null || data.get().issuedAt().isAfter(latest.issuedAt()))) {
            latest = data.get();
          }
        }
        if (latest != null) {
          sessions.add(
              new SessionInfo(familyId, latest.deviceInfo(), latest.sourceIp(), latest.issuedAt()));
        } else {
          jedis.srem(userKey, fid);
        }
      }
    }
    return sessions;
  }

  public void cacheTokenVersion(UUID orgId, UUID customerId, int version) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.set(verKey(orgId, customerId), String.valueOf(version));
    }
  }

  /** Drop the cached version so the filter's cache-miss path fails closed. */
  public void invalidateTokenVersion(UUID orgId, UUID customerId) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(verKey(orgId, customerId));
    }
  }

  public Optional<Integer> getCachedTokenVersion(UUID orgId, UUID customerId) {
    try (Jedis jedis = jedisPool.getResource()) {
      String val = jedis.get(verKey(orgId, customerId));
      if (val == null) return Optional.empty();
      return Optional.of(Integer.parseInt(val));
    }
  }

  private Optional<TokenData> findWithJedis(Jedis jedis, String tokenHash) {
    String json = jedis.get(tokenKey(tokenHash));
    if (json == null) return Optional.empty();
    try {
      return Optional.of(objectMapper.readValue(json, TokenData.class));
    } catch (JsonProcessingException e) {
      log.error("Failed to deserialize customer token data", e);
      return Optional.empty();
    }
  }
}
