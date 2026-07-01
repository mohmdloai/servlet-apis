package com.loai.inventory.api.dto;

/** Body for {@code POST /api/admin/users/{id}/org-roles}. */
public class OrgRoleRequest {
  private String orgId;
  private String role;

  public String getOrgId() {
    return orgId;
  }

  public void setOrgId(String orgId) {
    this.orgId = orgId;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }
}
