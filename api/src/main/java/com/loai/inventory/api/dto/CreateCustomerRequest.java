package com.loai.inventory.api.dto;

public class CreateCustomerRequest {
  private String email;
  private String passwordHash;

  public CreateCustomerRequest() {}

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }
}
