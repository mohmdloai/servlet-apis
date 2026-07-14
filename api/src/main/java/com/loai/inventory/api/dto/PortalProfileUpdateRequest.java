package com.loai.inventory.api.dto;

/** Body of {@code PATCH /api/portal/me}. Email is deliberately absent (not patchable here). */
public class PortalProfileUpdateRequest {
  private String name;
  private String phone;
  private String address;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }
}
