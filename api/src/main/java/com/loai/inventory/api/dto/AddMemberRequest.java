package com.loai.inventory.api.dto;

/** Body of {@code POST /api/orgs/{orgId}/members}: attach an existing user by email with a role. */
public class AddMemberRequest {
  private String email;
  private String role;

  public AddMemberRequest() {}

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }
}
