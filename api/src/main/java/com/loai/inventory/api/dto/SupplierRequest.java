package com.loai.inventory.api.dto;

/**
 * {@code POST|PUT /suppliers}. One shape for both verbs: {@code POST} requires {@code name}, {@code
 * PUT} is a merge — a null field is leave-unchanged ({@code PUT /api/orgs/{orgId}}'s convention).
 */
public class SupplierRequest {
  private String name;
  private String phone;
  private String email;
  private String address;
  private String notes;
  private Boolean active;

  public SupplierRequest() {}

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

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }
}
