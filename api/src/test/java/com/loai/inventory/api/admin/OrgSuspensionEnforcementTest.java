package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.OrgSuspendedException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The load-bearing half of slice 5: suspension only means something because {@link
 * AuthzHelper#requireOrgAccess} rejects a suspended org's members while preserving the platform
 * admin bypass. Exercises that gate directly with a stubbed org-status oracle.
 */
class OrgSuspensionEnforcementTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  @AfterEach
  void resetGate() {
    // The gate is a static global - never let a suspended-org stub leak into other test classes.
    AuthzHelper.configureOrgStatusGate(null);
  }

  private HttpServletRequest reqWith(SecurityContext ctx) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    return req;
  }

  private SecurityContext member(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private SecurityContext systemAdmin() {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  @Test
  void suspendedOrg_rejectsMember() {
    AuthzHelper.configureOrgStatusGate(orgId -> false); // everything suspended
    AuthorizationException ex =
        assertThrows(
            AuthorizationException.class,
            () ->
                AuthzHelper.requireOrgAccess(reqWith(member(OrgRole.STAFF)), ORG, OrgRole.VIEWER));
    assertEquals("Org suspended", ex.getMessage());
  }

  @Test
  void suspendedOrg_allowsSystemAdminBypass() {
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    assertDoesNotThrow(
        () -> AuthzHelper.requireOrgAccess(reqWith(systemAdmin()), ORG, OrgRole.OWNER));
  }

  @Test
  void activeOrg_allowsMemberWithSufficientRole() {
    AuthzHelper.configureOrgStatusGate(orgId -> true);
    assertDoesNotThrow(
        () -> AuthzHelper.requireOrgAccess(reqWith(member(OrgRole.STAFF)), ORG, OrgRole.VIEWER));
  }

  @Test
  void suspendedOrg_nonMemberGetsGenericNoAccess() {
    // An outsider must not be able to tell a suspended org from one they simply don't belong to -
    // both surface the same generic "No access", so suspension state can't be enumerated.
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    SecurityContext outsider =
        new SecurityContext(UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(), Set.of(), 0);
    AuthorizationException ex =
        assertThrows(
            AuthorizationException.class,
            () -> AuthzHelper.requireOrgAccess(reqWith(outsider), ORG, OrgRole.VIEWER));
    assertTrue(
        ex.getMessage().startsWith("No access"),
        "a non-member must not learn that the org is suspended");
  }

  @Test
  void suspendedOrg_suspensionTakesPrecedenceOverRoleCheck() {
    // Even a would-be role failure surfaces as "Org suspended" first - the org is simply closed.
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    AuthorizationException ex =
        assertThrows(
            AuthorizationException.class,
            () ->
                AuthzHelper.requireOrgAccess(reqWith(member(OrgRole.VIEWER)), ORG, OrgRole.OWNER));
    assertEquals("Org suspended", ex.getMessage());
  }

  // The suspended door (stories/support_ticket_reach.md)

  @Test
  void suspendedOrg_the403CarriesTheKind_soTheOrgAppCanLeadToSupport() {
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    AuthorizationException ex =
        assertThrows(
            AuthorizationException.class,
            () ->
                AuthzHelper.requireOrgAccess(reqWith(member(OrgRole.OWNER)), ORG, OrgRole.VIEWER));
    assertTrue(ex instanceof OrgSuspendedException, "the same 403, now typed");
    assertEquals("Org suspended", ex.getMessage());
    assertEquals(403, ex.getStatusCode());
    assertEquals(OrgSuspendedException.KIND, ApiErrors.body(ex).getKind());
  }

  @Test
  void suspendedOrg_nonMemberGetsNoKind_theEnumerationPropertyHolds() {
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    SecurityContext outsider =
        new SecurityContext(UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(), Set.of(), 0);
    AuthorizationException ex =
        assertThrows(
            AuthorizationException.class,
            () -> AuthzHelper.requireOrgAccess(reqWith(outsider), ORG, OrgRole.VIEWER));
    assertFalse(ex instanceof OrgSuspendedException);
    assertNull(
        ApiErrors.body(ex).getKind(), "an outsider must not learn that the org is suspended");
  }

  @Test
  void throughSuspension_admitsAMemberOfASuspendedOrg_atTheirRank() {
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    assertDoesNotThrow(
        () ->
            AuthzHelper.requireOrgAccessThroughSuspension(
                reqWith(member(OrgRole.STAFF)), ORG, OrgRole.STAFF));
    assertDoesNotThrow(
        () ->
            AuthzHelper.requireOrgAccessThroughSuspension(
                reqWith(member(OrgRole.VIEWER)), ORG, OrgRole.VIEWER));
  }

  @Test
  void throughSuspension_skipsExactlyTheGateStep_membershipAndRankStillApply() {
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    SecurityContext outsider =
        new SecurityContext(UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(), Set.of(), 0);
    AuthorizationException noAccess =
        assertThrows(
            AuthorizationException.class,
            () ->
                AuthzHelper.requireOrgAccessThroughSuspension(
                    reqWith(outsider), ORG, OrgRole.VIEWER));
    assertTrue(noAccess.getMessage().startsWith("No access"));
    assertFalse(noAccess instanceof OrgSuspendedException);

    AuthorizationException rank =
        assertThrows(
            AuthorizationException.class,
            () ->
                AuthzHelper.requireOrgAccessThroughSuspension(
                    reqWith(member(OrgRole.VIEWER)), ORG, OrgRole.STAFF));
    assertTrue(rank.getMessage().startsWith("Requires at least STAFF"));
  }

  @Test
  void throughSuspension_readOnlyImpersonationStillCannotWrite() {
    AuthzHelper.configureOrgStatusGate(orgId -> false);
    SecurityContext readOnly =
        new SecurityContext(
            UUID.randomUUID(),
            ActorType.USER,
            Set.of(),
            Map.of(ORG, Set.of(OrgRole.OWNER)),
            Set.of(),
            0,
            UUID.randomUUID(),
            com.loai.inventory.domain.model.ImpersonationTier.PLATFORM,
            ORG,
            /* impersonationReadOnly= */ true);
    AuthorizationException ex =
        assertThrows(
            AuthorizationException.class,
            () ->
                AuthzHelper.requireOrgAccessThroughSuspension(
                    reqWith(readOnly), ORG, OrgRole.STAFF));
    assertTrue(ex.getMessage().startsWith("Read-only impersonation"));
  }
}
