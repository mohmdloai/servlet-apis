package com.loai.inventory.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class JwtUtil {

  private final SecretKey key;
  private final long accessTtlMillis;

  public JwtUtil(String base64Secret, long accessTtlMillis) {
    if (base64Secret == null || base64Secret.isBlank()) {
      throw new IllegalArgumentException("JWT secret must not be null or blank");
    }
    byte[] decoded = Base64.getDecoder().decode(base64Secret);
    if (decoded.length < 32) {
      throw new IllegalArgumentException(
          "JWT secret must be at least 32 bytes after Base64 decode, got " + decoded.length);
    }
    this.key = new SecretKeySpec(decoded, "HmacSHA256");
    this.accessTtlMillis = accessTtlMillis;
  }

  public String generateAccessToken(
      UUID userId,
      String actorType,
      Map<UUID, Set<String>> orgRoles,
      Set<String> systemRoles,
      Set<String> allowedActions,
      int tokenVersion) {

    long now = System.currentTimeMillis();
    var builder =
        Jwts.builder()
            .subject(userId.toString())
            .claim("actor_type", actorType)
            .claim("token_version", tokenVersion)
            .issuedAt(new Date(now))
            .expiration(new Date(now + accessTtlMillis));

    if (systemRoles != null && !systemRoles.isEmpty()) {
      builder.claim("system_roles", List.copyOf(systemRoles));
    }
    if (orgRoles != null && !orgRoles.isEmpty()) {
      builder.claim("org_roles", orgRoles);
    }
    if (allowedActions != null && !allowedActions.isEmpty()) {
      builder.claim("allowed_actions", List.copyOf(allowedActions));
    }

    return builder.signWith(key).compact();
  }

  public Claims parseAndVerify(String token) throws JwtException {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }

  public long getAccessTtlMillis() {
    return accessTtlMillis;
  }
}
