package com.loai.inventory.api.dto;

public class CreateCustomerRequest {
  private String email;

  public CreateCustomerRequest() {}

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }
}
