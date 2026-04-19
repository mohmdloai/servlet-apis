package com.loai.inventory.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class RefreshTokenStore {
  private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
  private static final long TOKEN_TTL_SECONDS = 7 * 24 * 3600; // 7 days
  private static final long FAMILY_TTL_SECONDS = 8 * 24 * 3600; // 8 days (outlives tokens)

  private final JedisPool jedisPool;
  private final ObjectMapper objectMapper;

  public RefreshTokenStore(JedisPool jedisPool) {
    this.jedisPool = jedisPool;
    this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  }

  public record TokenData(
      UUID userId, UUID familyId, String deviceInfo, String sourceIp, Instant issuedAt) {}

  public record SessionInfo(
      UUID familyId, String deviceInfo, String sourceIp, Instant lastIssuedAt) {}

  public static String hashToken(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  public void store(String tokenHash, TokenData data) {
    String tokenKey = "rt:" + tokenHash;
    String familyKey = "rt:family:" + data.familyId();
    String userKey = "rt:user:" + data.userId();

    try (Jedis jedis = jedisPool.getResource()) {
      String json = objectMapper.writeValueAsString(data);
      jedis.setex(tokenKey, TOKEN_TTL_SECONDS, json);
      jedis.sadd(familyKey, tokenHash);
      jedis.expire(familyKey, FAMILY_TTL_SECONDS);
      jedis.sadd(userKey, data.familyId().toString());
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize token data", e);
    }
  }

  public Optional<TokenData> find(String tokenHash) {
    String tokenKey = "rt:" + tokenHash;
    try (Jedis jedis = jedisPool.getResource()) {
      String json = jedis.get(tokenKey);
      if (json == null) return Optional.empty();
      return Optional.of(objectMapper.readValue(json, TokenData.class));
    } catch (JsonProcessingException e) {
      log.error("Failed to deserialize token data", e);
      return Optional.empty();
    }
  }

  public void revoke(String tokenHash) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del("rt:" + tokenHash);
    }
  }

  public boolean familyExists(UUID familyId) {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.exists("rt:family:" + familyId);
    }
  }

  /**
   * Revokes a refresh token family. Returns true if the family belonged to the user and was
   * revoked, false if the family does not belong to this user.
   */
  public boolean revokeFamily(UUID familyId, UUID userId) {
    String familyKey = "rt:family:" + familyId;
    String userKey = "rt:user:" + userId;
    try (Jedis jedis = jedisPool.getResource()) {
      // Verify the family belongs to this user before revoking
      if (!jedis.sismember(userKey, familyId.toString())) {
        return false;
      }
      Set<String> tokenHashes = jedis.smembers(familyKey);
      if (tokenHashes != null) {
        for (String hash : tokenHashes) {
          jedis.del("rt:" + hash);
        }
      }
      jedis.del(familyKey);
      jedis.srem(userKey, familyId.toString());
      return true;
    }
  }

  public void revokeAllForUser(UUID userId) {
    String userKey = "rt:user:" + userId;
    try (Jedis jedis = jedisPool.getResource()) {
      Set<String> familyIds = jedis.smembers(userKey);
      if (familyIds != null) {
        for (String fid : familyIds) {
          String familyKey = "rt:family:" + fid;
          Set<String> tokenHashes = jedis.smembers(familyKey);
          if (tokenHashes != null) {
            for (String hash : tokenHashes) {
              jedis.del("rt:" + hash);
            }
          }
          jedis.del(familyKey);
        }
      }
      jedis.del(userKey);
    }
  }

  public List<SessionInfo> listSessions(UUID userId) {
    String userKey = "rt:user:" + userId;
    List<SessionInfo> sessions = new ArrayList<>();
    try (Jedis jedis = jedisPool.getResource()) {
      Set<String> familyIds = jedis.smembers(userKey);
      if (familyIds == null) return sessions;
      for (String fid : familyIds) {
        UUID familyId = UUID.fromString(fid);
        String familyKey = "rt:family:" + fid;
        Set<String> tokenHashes = jedis.smembers(familyKey);
        if (tokenHashes == null || tokenHashes.isEmpty()) {
          jedis.srem(userKey, fid);
          continue;
        }
        // Use the most recent token in the family for session info
        TokenData latest = null;
        for (String hash : tokenHashes) {
          Optional<TokenData> data = findWithJedis(jedis, hash);
          if (data.isPresent()) {
            if (latest == null || data.get().issuedAt().isAfter(latest.issuedAt())) {
              latest = data.get();
            }
          }
        }
        if (latest != null) {
          sessions.add(
              new SessionInfo(familyId, latest.deviceInfo(), latest.sourceIp(), latest.issuedAt()));
        } else {
          // All tokens in this family expired — clean up the stale family reference
          jedis.srem(userKey, fid);
        }
      }
    }
    return sessions;
  }

  public void cacheTokenVersion(UUID userId, int version) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.set("user:ver:" + userId, String.valueOf(version));
    }
  }

  public Optional<Integer> getCachedTokenVersion(UUID userId) {
    try (Jedis jedis = jedisPool.getResource()) {
      String val = jedis.get("user:ver:" + userId);
      if (val == null) return Optional.empty();
      return Optional.of(Integer.parseInt(val));
    }
  }

  private Optional<TokenData> findWithJedis(Jedis jedis, String tokenHash) {
    String json = jedis.get("rt:" + tokenHash);
    if (json == null) return Optional.empty();
    try {
      return Optional.of(objectMapper.readValue(json, TokenData.class));
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }
}
