package com.loai.inventory.api.dto;

/** Body of {@code POST /api/auth/verify-email} (story 88) — the emailed one-shot token. */
public class VerifyEmailRequest {
  private String token;

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }
}
