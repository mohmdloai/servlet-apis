package com.loai.inventory.api.dto;

/** Body for {@code POST /api/admin/users/{id}/system-roles}. */
public class SystemRoleRequest {
  private String role;

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }
}
