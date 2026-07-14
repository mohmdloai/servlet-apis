package com.loai.inventory.api.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two-plane boundary at the token level (AC5) — the three independent barriers of the epic
 * threat model, none of which needs a running server:
 *
 * <ol>
 *   <li><b>Separate signing key</b> — a customer token cannot verify under the staff key, and vice
 *       versa (a {@link JwtException}).
 *   <li><b>{@code aud} claim</b> — customer tokens carry {@code aud=customer}, staff tokens {@code
 *       aud=staff}; a pre-hardening staff token carries none (the grace window).
 *   <li><b>No authority in a customer token</b> — no {@code org_roles}/{@code system_roles}.
 * </ol>
 *
 * The filters' path/aud acceptance is exercised end-to-end in the servlet layer; this pins the
 * cryptographic + claim guarantees the filters rely on.
 */
class CustomerJwtIsolationTest {

  static final String STAFF_SECRET =
      Base64.getEncoder().encodeToString("staff-secret-key-32-bytes-long!!".getBytes());
  static final String CUSTOMER_SECRET =
      Base64.getEncoder().encodeToString("customer-secret-key-32bytes-long".getBytes());
  static final long TTL = 900_000L;

  final JwtUtil staffJwt = new JwtUtil(STAFF_SECRET, TTL, "staff");
  final JwtUtil customerJwt = new JwtUtil(CUSTOMER_SECRET, TTL, "customer");

  private String staffToken() {
    return staffJwt.generateAccessToken(
        UUID.randomUUID(),
        "USER",
        Map.of(UUID.randomUUID(), Set.of("OWNER")),
        Set.of("ADMIN"),
        Set.of(),
        1,
        UUID.randomUUID());
  }

  private String customerToken() {
    return customerJwt.generateCustomerAccessToken(
        UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
  }

  @Test
  void keysAreDisjoint_neitherPlaneVerifiesTheOther() {
    assertThrows(
        JwtException.class,
        () -> customerJwt.parseAndVerify(staffToken()),
        "staff token must not verify under the customer key");
    assertThrows(
        JwtException.class,
        () -> staffJwt.parseAndVerify(customerToken()),
        "customer token must not verify under the staff key");
  }

  @Test
  void audienceMarksThePlane() {
    assertTrue(customerJwt.parseAndVerify(customerToken()).getAudience().contains("customer"));
    assertTrue(staffJwt.parseAndVerify(staffToken()).getAudience().contains("staff"));
  }

  @Test
  void preHardeningStaffToken_hasNoAudience_soTheFilterGraceApplies() {
    // A JwtUtil with no audience mints a token with no aud claim (a live session from before the
    // deploy). JwtAuthFilter accepts a missing aud; it only rejects "customer".
    JwtUtil legacyStaff = new JwtUtil(STAFF_SECRET, TTL);
    String legacy =
        legacyStaff.generateAccessToken(
            UUID.randomUUID(), "USER", Map.of(), Set.of(), Set.of(), 1, UUID.randomUUID());
    Set<String> aud = staffJwt.parseAndVerify(legacy).getAudience();
    assertTrue(aud == null || aud.isEmpty(), "legacy staff token carries no aud");
  }

  @Test
  void customerTokenCarriesNoAuthority() {
    Claims claims = customerJwt.parseAndVerify(customerToken());
    assertEquals("CUSTOMER", claims.get("actor_type", String.class));
    assertNull(claims.get("org_roles"), "no org roles in a customer token");
    assertNull(claims.get("system_roles"), "no system roles in a customer token");
    assertNull(claims.get("allowed_actions"), "no allowed_actions in a customer token");
    assertFalse(
        claims.getAudience().contains("staff"), "a customer token is never accepted as staff");
  }
}
