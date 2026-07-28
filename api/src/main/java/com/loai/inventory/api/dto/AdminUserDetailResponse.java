package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.UserOrgRole;
import com.loai.inventory.service.platform.UserAdminService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full platform view of one user: identity, status, roles, and active-session count.
 *
 * <p>{@code emailVerified} / {@code emailVerifiedAt} follow the same both-fields rule as {@link
 * AdminUserResponse}, for the reason stated there — the list and the detail must not disagree about
 * how "never verified" is spelled on the wire.
 */
public record AdminUserDetailResponse(
    UUID id,
    String email,
    String actorType,
    boolean active,
    boolean emailVerified,
    OffsetDateTime emailVerifiedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<String> systemRoles,
    List<OrgRoleEntry> orgRoles,
    long activeSessionCount) {

  /** One (org, role) grant the user holds. */
  public record OrgRoleEntry(UUID orgId, String role) {
    static OrgRoleEntry from(UserOrgRole r) {
      return new OrgRoleEntry(r.getOrgId(), r.getRole().name());
    }
  }

  public static AdminUserDetailResponse from(UserAdminService.UserDetail d) {
    return new AdminUserDetailResponse(
        d.user().getId(),
        d.user().getEmail(),
        d.user().getActorType().name(),
        d.user().isActive(),
        d.user().getEmailVerifiedAt() != null,
        d.user().getEmailVerifiedAt(),
        d.user().getCreatedAt(),
        d.user().getUpdatedAt(),
        d.systemRoles().stream().map(Enum::name).sorted().toList(),
        d.orgRoles().stream().map(OrgRoleEntry::from).toList(),
        d.activeSessionCount());
  }
}
