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
    int tokenVersion) {

  public ActorContext toActorContext() {
    return switch (actorType) {
      case USER -> ActorContext.user(actorId.toString());
      case SERVICE -> ActorContext.service(actorId.toString());
      case SYSTEM -> ActorContext.system(actorId.toString());
      case MIGRATION -> ActorContext.migration(actorId.toString());
    };
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
}
