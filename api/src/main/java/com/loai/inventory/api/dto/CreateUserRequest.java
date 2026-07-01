package com.loai.inventory.api.dto;

/**
 * Body for {@code POST /api/admin/users}. {@code password} is optional; {@code actorType} defaults
 * to USER.
 */
public class CreateUserRequest {
  private String email;
  private String password;
  private String actorType;

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getActorType() {
    return actorType;
  }

  public void setActorType(String actorType) {
    this.actorType = actorType;
  }
}
