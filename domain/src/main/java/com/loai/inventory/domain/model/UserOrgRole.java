package com.loai.inventory.domain.model;

import java.util.UUID;

public class UserOrgRole {
  private UUID userId;
  private UUID orgId;
  private OrgRole role;

  public UserOrgRole() {}

  public UserOrgRole(UUID userId, UUID orgId, OrgRole role) {
    this.userId = userId;
    this.orgId = orgId;
    this.role = role;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public OrgRole getRole() {
    return role;
  }

  public void setRole(OrgRole role) {
    this.role = role;
  }
}
