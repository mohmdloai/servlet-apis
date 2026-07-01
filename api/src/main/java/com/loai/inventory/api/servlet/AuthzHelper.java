package com.loai.inventory.api.servlet;

import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
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

  /** Answers "is this org active?" - pluggable so the hot-path check can read a Redis mirror. */
  @FunctionalInterface
  public interface OrgStatusGate {
    boolean isActive(UUID orgId);
  }

  // Default: no suspension enforcement (every org active). AppConfig installs the real,
  // Redis-backed gate at boot; unit tests that don't configure it keep their existing behaviour.
  private static volatile OrgStatusGate orgStatusGate = orgId -> true;

  /**
   * Install the org-active gate consulted by {@link #requireOrgAccess}. Null resets to "all
   * active".
   */
  public static void configureOrgStatusGate(OrgStatusGate gate) {
    orgStatusGate = gate == null ? (orgId -> true) : gate;
  }

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

  /**
   * Platform read tier: caller holds {@link SystemRole#ADMIN} or {@link SystemRole#SUPPORT}. This
   * is the VIEWER-equivalent at the platform tier - it gates the cross-org read console and finally
   * gives SUPPORT a purpose beyond read-only impersonation. Mutations still use {@link
   * #requireAdmin}.
   */
  public static SecurityContext requirePlatformRead(HttpServletRequest req) {
    SecurityContext ctx = requireAuth(req);
    if (!ctx.hasSystemRole(SystemRole.ADMIN) && !ctx.hasSystemRole(SystemRole.SUPPORT)) {
      throw new AuthorizationException("Requires ADMIN or SUPPORT role");
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
    // Platform ADMIN bypasses org checks entirely - including suspension, so an admin can enter a
    // suspended org to fix it.
    if (ctx.isSystemAdmin()) return ctx;

    // A suspended org rejects all its normal members, whatever their role.
    if (!orgStatusGate.isActive(orgId)) {
      throw new AuthorizationException("Org suspended");
    }

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
