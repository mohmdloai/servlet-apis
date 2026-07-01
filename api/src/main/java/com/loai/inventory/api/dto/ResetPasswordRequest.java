package com.loai.inventory.api.dto;

/** Body for {@code POST /api/admin/users/{id}/reset-password}. */
public class ResetPasswordRequest {
  private String password;

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
