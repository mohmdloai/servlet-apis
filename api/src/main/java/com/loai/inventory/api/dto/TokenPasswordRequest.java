package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/auth/reset-password} and {@code POST /api/auth/activate}: a single-use
 * token ({@code token}) plus the new password ({@code new_password}) to set.
 */
public class TokenPasswordRequest {
  private String token;
  private String newPassword;

  public TokenPasswordRequest() {}

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(String newPassword) {
    this.newPassword = newPassword;
  }
}
