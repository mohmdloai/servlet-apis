package com.loai.inventory.domain.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record SecurityContext(
    UUID actorId,
    ActorType actorType,
    Set<SystemRole> systemRoles,
    Map<UUID, Set<OrgRole>> orgRoles,
    Set<String> allowedActions,
    int tokenVersion,
    UUID impersonatorId,
    ImpersonationTier impersonationTier,
    UUID impersonationScopeOrg,
    boolean impersonationReadOnly) {

  /**
   * Back-compat constructor for non-impersonation contexts (service/system actors, tests). A normal
   * session carries no {@code act} claim.
   */
  public SecurityContext(
      UUID actorId,
      ActorType actorType,
      Set<SystemRole> systemRoles,
      Map<UUID, Set<OrgRole>> orgRoles,
      Set<String> allowedActions,
      int tokenVersion) {
    this(
        actorId,
        actorType,
        systemRoles,
        orgRoles,
        allowedActions,
        tokenVersion,
        null,
        null,
        null,
        false);
  }

  public ActorContext toActorContext() {
    ActorContext base =
        switch (actorType) {
          case USER -> ActorContext.user(actorId.toString());
          case SERVICE -> ActorContext.service(actorId.toString());
          case SYSTEM -> ActorContext.system(actorId.toString());
          case MIGRATION -> ActorContext.migration(actorId.toString());
        };
    // During an overlay the principal (sub) is the target; the real driver rides along in metadata.
    return impersonatorId == null
        ? base
        : new ActorContext(base.actorId(), base.actorType(), impersonatorId);
  }

  public boolean hasSystemRole(SystemRole role) {
    return systemRoles != null && systemRoles.contains(role);
  }

  public boolean hasOrgRole(UUID orgId, OrgRole role) {
    if (orgRoles == null) return false;
    Set<OrgRole> roles = orgRoles.get(orgId);
    return roles != null && roles.contains(role);
  }

  public boolean isSystemAdmin() {
    return hasSystemRole(SystemRole.ADMIN);
  }

  public boolean isService() {
    return actorType == ActorType.SERVICE;
  }

  /** True while this request is an impersonation overlay (carries an {@code act} claim). */
  public boolean isImpersonating() {
    return impersonatorId != null;
  }
}
