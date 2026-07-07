package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/auth/register}. {@code org_name} is optional — when present the
 * registrant also gets a new org with themselves as OWNER.
 */
public class RegisterRequest {
  private String email;
  private String password;
  private String orgName;

  public RegisterRequest() {}

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

  public String getOrgName() {
    return orgName;
  }

  public void setOrgName(String orgName) {
    this.orgName = orgName;
  }
}
