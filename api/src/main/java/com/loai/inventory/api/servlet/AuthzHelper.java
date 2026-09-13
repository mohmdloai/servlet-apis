package com.loai.inventory.api.servlet;

import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.OrgSuspendedException;
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

  /**
   * The support desk ({@code stories/support_tickets.md}): caller holds {@link SystemRole#ADMIN} or
   * {@link SystemRole#SUPPORT}. <strong>The one place the SUPPORT tier writes.</strong> A ticket
   * reply, resolve or close changes no tenant data — it is the desk's own record — so it is not the
   * write {@link #requireAdmin} guards, and a read-only tier with no job would be a tier with no
   * point. Named for the desk rather than reusing {@link #requirePlatformRead} so the next reader
   * does not have to wonder why a "read" gate admits a POST.
   */
  public static SecurityContext requireSupportDesk(HttpServletRequest req) {
    SecurityContext ctx = requireAuth(req);
    if (!ctx.hasSystemRole(SystemRole.ADMIN) && !ctx.hasSystemRole(SystemRole.SUPPORT)) {
      throw new AuthorizationException("Requires a platform role (ADMIN or SUPPORT)");
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
   * 403 otherwise — {@link OrgSuspendedException} (kind {@code ORG_SUSPENDED}) for a member of a
   * suspended org, so the org app can lead them to the one door that stays open.
   */
  public static SecurityContext requireOrgAccess(
      HttpServletRequest req, UUID orgId, OrgRole minRole) {
    return requireOrgAccess(req, orgId, minRole, /* enforceSuspension= */ true);
  }

  /**
   * {@link #requireOrgAccess} with the {@link OrgStatusGate} step skipped — membership, read-only
   * impersonation and rank all still apply ({@code stories/support_ticket_reach.md}). Support is
   * the one resource a suspended tenant must still reach, so this has <b>exactly one caller</b>:
   * {@code SupportTicketHandler}. A {@code PENDING} org's members are unverified owners who cannot
   * log in; the variant admits them too, harmlessly — nothing reaches it.
   */
  public static SecurityContext requireOrgAccessThroughSuspension(
      HttpServletRequest req, UUID orgId, OrgRole minRole) {
    return requireOrgAccess(req, orgId, minRole, /* enforceSuspension= */ false);
  }

  private static SecurityContext requireOrgAccess(
      HttpServletRequest req, UUID orgId, OrgRole minRole, boolean enforceSuspension) {
    SecurityContext ctx = requireAuth(req);
    // A read-only (SUPPORT view-as) overlay may read but never write, whatever the target could do.
    if (ctx.impersonationReadOnly() && RANK.get(minRole) > RANK.get(OrgRole.VIEWER)) {
      throw new AuthorizationException("Read-only impersonation cannot perform writes");
    }
    // Platform ADMIN bypasses org checks entirely - including suspension, so an admin can enter a
    // suspended org to fix it.
    if (ctx.isSystemAdmin()) return ctx;

    // Membership is checked before suspension so a non-member gets the same generic "No access"
    // whether or not the org is suspended - otherwise an outsider could probe which orgs are
    // suspended by comparing the two 403s (the kind below is only ever thrown past this line).
    Set<OrgRole> roles = ctx.orgRoles() == null ? null : ctx.orgRoles().get(orgId);
    if (roles == null || roles.isEmpty()) {
      throw new AuthorizationException("No access to org " + orgId);
    }

    // A suspended org then rejects all its members, whatever their role — except through the
    // support door.
    if (enforceSuspension && !orgStatusGate.isActive(orgId)) {
      throw new OrgSuspendedException();
    }

    int needed = RANK.get(minRole);
    boolean ok = roles.stream().anyMatch(r -> RANK.get(r) >= needed);
    if (!ok) {
      throw new AuthorizationException("Requires at least " + minRole + " role in org " + orgId);
    }
    return ctx;
  }

  /**
   * Does the caller hold MANAGER (or OWNER) in {@code orgId}, or platform ADMIN? The one predicate
   * behind every "money-shaped" question the org plane asks after access is already granted: the
   * counter-discount gate (stories/counter_discount.md) and, since
   * stories/product_cost_and_margin.md, whether a cost or any cost-derived figure is written to the
   * response at all. A pure check — it throws nothing and consults no gate — so callers decide
   * between "omit the field" and "403". Defined once so the four consumers cannot drift onto four
   * definitions.
   */
  public static boolean hasManagerAuthority(SecurityContext ctx, UUID orgId) {
    if (ctx == null) {
      return false;
    }
    if (ctx.isSystemAdmin()) {
      return true;
    }
    Set<OrgRole> roles = ctx.orgRoles() == null ? null : ctx.orgRoles().get(orgId);
    return roles != null && roles.stream().anyMatch(r -> RANK.get(r) >= RANK.get(OrgRole.MANAGER));
  }
}
