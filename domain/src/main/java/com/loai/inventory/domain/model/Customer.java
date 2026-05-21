package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class Customer {
  private UUID id;
  private UUID orgId;
  private String email;
  private String name;
  private String phone;
  private String address;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Customer() {}

  public Customer(
      UUID id,
      UUID orgId,
      String email,
      String name,
      String phone,
      String address,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.email = email;
    this.name = name;
    this.phone = phone;
    this.address = address;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

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

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public String toString() {
    return "Customer{id=" + id + ", orgId=" + orgId + ", email='" + email + "'}";
  }
}
