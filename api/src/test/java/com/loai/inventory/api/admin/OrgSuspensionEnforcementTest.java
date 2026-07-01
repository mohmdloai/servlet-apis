package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AuthorizationException;
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
}
