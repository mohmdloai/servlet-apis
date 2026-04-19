package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Customer;
import java.time.OffsetDateTime;
import java.util.UUID;

public class CustomerResponse {
  private UUID id;
  private UUID orgId;
  private String email;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private CustomerResponse() {}

  public static CustomerResponse from(Customer c) {
    CustomerResponse r = new CustomerResponse();
    r.id = c.getId();
    r.orgId = c.getOrgId();
    r.email = c.getEmail();
    r.createdAt = c.getCreatedAt();
    r.updatedAt = c.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getEmail() {
    return email;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
