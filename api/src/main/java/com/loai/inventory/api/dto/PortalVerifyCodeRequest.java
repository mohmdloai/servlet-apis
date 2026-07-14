package com.loai.inventory.api.dto;

/** Body of {@code POST /api/public/{orgSlug}/portal/verify-code}. */
public class PortalVerifyCodeRequest {
  private String email;
  private String code;

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }
}
