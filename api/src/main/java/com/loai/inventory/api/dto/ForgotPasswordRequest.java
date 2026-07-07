package com.loai.inventory.api.dto;

/** Body of {@code POST /api/auth/forgot-password}. */
public class ForgotPasswordRequest {
  private String email;

  public ForgotPasswordRequest() {}

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }
}
