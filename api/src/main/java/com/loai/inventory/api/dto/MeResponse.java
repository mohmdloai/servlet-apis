package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The caller's own profile ({@code GET /api/me}). Identity fields come from a fresh {@code
 * app_user} read; roles come from the live (token-version-validated) {@link SecurityContext},
 * flattened one entry per held role. {@code impersonation} lets a SUPPORT view-as UI disable writes
 * rather than bounce them. See {@code stories/09_st_org_settings.md}.
 */
public record MeResponse(
    UUID userId,
    String email,
    String displayName,
    String actorType,
    boolean active,
    List<String> systemRoles,
    List<OrgRoleEntry> orgRoles,
    Impersonation impersonation) {

  /** One (org, role) grant the caller holds. */
  public record OrgRoleEntry(UUID orgId, String role) {}

  /** The view-as overlay signal; {@code readOnly=false} and null fields for a normal session. */
  public record Impersonation(boolean readOnly, UUID impersonatorId, String tier, UUID scopeOrg) {}

  public static MeResponse from(AppUser user, SecurityContext ctx) {
    List<String> systemRoles =
        ctx.systemRoles() == null
            ? List.of()
            : ctx.systemRoles().stream().map(SystemRole::name).sorted().toList();

    List<OrgRoleEntry> orgRoles = new ArrayList<>();
    if (ctx.orgRoles() != null) {
      ctx.orgRoles()
          .forEach(
              (orgId, roles) -> {
                for (OrgRole role : roles) {
                  orgRoles.add(new OrgRoleEntry(orgId, role.name()));
                }
              });
    }

    Impersonation impersonation =
        ctx.isImpersonating()
            ? new Impersonation(
                ctx.impersonationReadOnly(),
                ctx.impersonatorId(),
                ctx.impersonationTier() == null ? null : ctx.impersonationTier().name(),
                ctx.impersonationScopeOrg())
            : new Impersonation(false, null, null, null);

    return new MeResponse(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        user.getActorType().name(),
        user.isActive(),
        systemRoles,
        orgRoles,
        impersonation);
  }
}
