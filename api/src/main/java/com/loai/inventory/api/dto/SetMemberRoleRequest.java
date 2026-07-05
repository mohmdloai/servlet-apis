package com.loai.inventory.api.dto;

/** Body of {@code PUT /api/orgs/{orgId}/members/{userId}}: the member's new sole role. */
public class SetMemberRoleRequest {
  private String role;

  public SetMemberRoleRequest() {}

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }
}
