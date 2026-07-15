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
  private final String audience;

  public JwtUtil(String base64Secret, long accessTtlMillis) {
    this(base64Secret, accessTtlMillis, null);
  }

  /**
   * Build a signer/verifier that stamps every minted token with a fixed {@code aud} (audience) —
   * the plane marker for token-type separation. The staff instance passes {@code "staff"}; the
   * customer-portal instance ({@code CUSTOMER_JWT_SECRET}) passes {@code "customer"}. A {@code
   * null} audience mints no {@code aud} claim (back-compat for tests and the pre-hardening
   * callers). See {@code portal_auth_core.md} §Identity + the epic's locked decision #2.
   */
  public JwtUtil(String base64Secret, long accessTtlMillis, String audience) {
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
    this.audience = audience;
  }

  /**
   * Mint a customer-portal access token. Deliberately carries <em>only</em> {@code sub=customerId},
   * {@code actor_type=CUSTOMER}, {@code org_id}, {@code token_version}, {@code fam}, {@code aud}
   * (via the instance's audience), {@code iat}, {@code exp} — <em>never</em> {@code
   * org_roles}/{@code system_roles}/{@code allowed_actions}. A customer bears no authority beyond
   * being that customer of that org. (portal_auth_core.md §Identity.)
   */
  public String generateCustomerAccessToken(
      UUID customerId, UUID orgId, int tokenVersion, UUID familyId) {
    long now = System.currentTimeMillis();
    var builder =
        Jwts.builder()
            .subject(customerId.toString())
            .claim("actor_type", ActorTypeName.CUSTOMER)
            .claim("org_id", orgId.toString())
            .claim("token_version", tokenVersion)
            .claim("fam", familyId.toString())
            .issuedAt(new Date(now))
            .expiration(new Date(now + accessTtlMillis));
    if (audience != null) {
      builder.audience().add(audience).and();
    }
    return builder.signWith(key).compact();
  }

  /**
   * The {@code actor_type} literal for a customer token — kept as a constant here so {@code common}
   * need not depend on the {@code domain} {@code ActorType} enum. It equals {@code
   * ActorType.CUSTOMER.name()} and the {@code CustomerAuthFilter} asserts the match.
   */
  private static final class ActorTypeName {
    static final String CUSTOMER = "CUSTOMER";
  }

  public String generateAccessToken(
      UUID userId,
      String actorType,
      Map<UUID, Set<String>> orgRoles,
      Set<String> systemRoles,
      Set<String> allowedActions,
      int tokenVersion) {
    return generateAccessToken(
        userId,
        actorType,
        orgRoles,
        systemRoles,
        allowedActions,
        tokenVersion,
        null,
        null,
        null,
        null,
        null,
        accessTtlMillis);
  }

  /**
   * Mint a device-bound access token — carries a {@code fam} claim (the refresh-token family = one
   * device) so the filter can enforce a per-device access-token kill-switch. Used by login/refresh.
   */
  public String generateAccessToken(
      UUID userId,
      String actorType,
      Map<UUID, Set<String>> orgRoles,
      Set<String> systemRoles,
      Set<String> allowedActions,
      int tokenVersion,
      UUID familyId) {
    return generateAccessToken(
        userId,
        actorType,
        orgRoles,
        systemRoles,
        allowedActions,
        tokenVersion,
        familyId,
        null,
        null,
        null,
        null,
        accessTtlMillis);
  }

  /**
   * Mint an impersonation-overlay access token. {@code sub} is the target ({@code userId}); the
   * {@code act*} claims record the real driver, the tier, the confined org (ORG tier), and the
   * read-only flag (SUPPORT view-as). Any {@code act*} arg left null omits its claim, so passing
   * all nulls with {@code ttlMillis == accessTtlMillis} is exactly a normal token. An overlay
   * passes {@code familyId == null} — it is device-less, killed by TTL or the target's logout-all,
   * not by a per-device revoke.
   */
  public String generateAccessToken(
      UUID userId,
      String actorType,
      Map<UUID, Set<String>> orgRoles,
      Set<String> systemRoles,
      Set<String> allowedActions,
      int tokenVersion,
      UUID familyId,
      UUID actId,
      String actTier,
      UUID actScopeOrg,
      String actMode,
      long ttlMillis) {

    long now = System.currentTimeMillis();
    var builder =
        Jwts.builder()
            .subject(userId.toString())
            .claim("actor_type", actorType)
            .claim("token_version", tokenVersion)
            .issuedAt(new Date(now))
            .expiration(new Date(now + ttlMillis));

    // Plane marker: staff tokens now carry aud="staff" (the JwtAuthFilter accepts staff or a
    // missing aud during the grace window, and rejects "customer"). Null audience = no claim.
    if (audience != null) {
      builder.audience().add(audience).and();
    }

    if (familyId != null) {
      builder.claim("fam", familyId.toString());
    }

    if (systemRoles != null && !systemRoles.isEmpty()) {
      builder.claim("system_roles", List.copyOf(systemRoles));
    }
    if (orgRoles != null && !orgRoles.isEmpty()) {
      builder.claim("org_roles", orgRoles);
    }
    if (allowedActions != null && !allowedActions.isEmpty()) {
      builder.claim("allowed_actions", List.copyOf(allowedActions));
    }
    if (actId != null) {
      builder.claim("act", actId.toString());
    }
    if (actTier != null) {
      builder.claim("act_tier", actTier);
    }
    if (actScopeOrg != null) {
      builder.claim("act_scope_org", actScopeOrg.toString());
    }
    if (actMode != null) {
      builder.claim("act_mode", actMode);
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
