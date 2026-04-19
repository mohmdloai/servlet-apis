package com.loai.inventory.api.dto;

public class UpdateCustomerRequest {
  private String email;

  public UpdateCustomerRequest() {}

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }
}
