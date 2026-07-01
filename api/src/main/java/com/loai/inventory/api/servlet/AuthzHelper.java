package com.loai.inventory.api.servlet;

import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;

public final class AuthzHelper {

  /**
   * Role hierarchy: OWNER > MANAGER > STAFF > VIEWER. A user with OWNER satisfies any minimum;
   * VIEWER satisfies only VIEWER.
   */
  private static final java.util.Map<OrgRole, Integer> RANK =
      java.util.Map.of(
          OrgRole.VIEWER, 1,
          OrgRole.STAFF, 2,
          OrgRole.MANAGER, 3,
          OrgRole.OWNER, 4);

  private AuthzHelper() {}

  public static SecurityContext requireAuth(HttpServletRequest req) {
    SecurityContext ctx = (SecurityContext) req.getAttribute(JwtAuthFilter.SECURITY_CONTEXT_ATTR);
    if (ctx == null) {
      throw new AuthenticationException("Authentication required");
    }
    return ctx;
  }

  public static SecurityContext requireAdmin(HttpServletRequest req) {
    SecurityContext ctx = requireAuth(req);
    if (!ctx.isSystemAdmin()) {
      throw new AuthorizationException("Requires ADMIN role");
    }
    return ctx;
  }

  public static SecurityContext requireAdminOrService(HttpServletRequest req) {
    SecurityContext ctx = requireAuth(req);
    if (!ctx.isSystemAdmin() && !ctx.isService()) {
      throw new AuthorizationException("Requires ADMIN role or SERVICE actor");
    }
    return ctx;
  }

  public static SecurityContext requireAdminOrSelf(HttpServletRequest req, UUID resourceOwnerId) {
    SecurityContext ctx = requireAuth(req);
    if (!ctx.isSystemAdmin() && !ctx.actorId().equals(resourceOwnerId)) {
      throw new AuthorizationException("Requires ADMIN role or resource ownership");
    }
    return ctx;
  }

  /**
   * Verify the caller has at least {@code minRole} in {@code orgId}, or is a system ADMIN. Throws
   * 403 otherwise.
   */
  public static SecurityContext requireOrgAccess(
      HttpServletRequest req, UUID orgId, OrgRole minRole) {
    SecurityContext ctx = requireAuth(req);
    // A read-only (SUPPORT view-as) overlay may read but never write, whatever the target could do.
    if (ctx.impersonationReadOnly() && RANK.get(minRole) > RANK.get(OrgRole.VIEWER)) {
      throw new AuthorizationException("Read-only impersonation cannot perform writes");
    }
    if (ctx.isSystemAdmin()) return ctx;

    Set<OrgRole> roles = ctx.orgRoles() == null ? null : ctx.orgRoles().get(orgId);
    if (roles == null || roles.isEmpty()) {
      throw new AuthorizationException("No access to org " + orgId);
    }

    int needed = RANK.get(minRole);
    boolean ok = roles.stream().anyMatch(r -> RANK.get(r) >= needed);
    if (!ok) {
      throw new AuthorizationException("Requires at least " + minRole + " role in org " + orgId);
    }
    return ctx;
  }
}
