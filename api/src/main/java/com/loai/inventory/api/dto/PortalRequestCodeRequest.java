package com.loai.inventory.api.dto;

/** Body of {@code POST /api/public/{orgSlug}/portal/request-code}. */
public class PortalRequestCodeRequest {
  private String email;

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }
}
