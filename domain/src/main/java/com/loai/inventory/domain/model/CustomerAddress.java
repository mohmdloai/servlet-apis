package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry in a customer's saved-address book (slice P4, {@code portal_addresses_reorder.md}). A
 * reusable, labelled delivery address the customer manages from the portal — distinct from {@link
 * Customer#getAddress()}, the single free-text line frozen onto an order at checkout. Exactly one
 * address per customer may carry {@link #isDefault()} (a partial unique index enforces it); orders
 * never reference this row, so deleting one never touches order history.
 */
public class CustomerAddress {
  private UUID id;
  private UUID orgId;
  private UUID customerId;
  private String label;
  private String recipient;
  private String phone;
  private String address;
  private boolean isDefault;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public CustomerAddress() {}

  public CustomerAddress(
      UUID id,
      UUID orgId,
      UUID customerId,
      String label,
      String recipient,
      String phone,
      String address,
      boolean isDefault,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.customerId = customerId;
    this.label = label;
    this.recipient = recipient;
    this.phone = phone;
    this.address = address;
    this.isDefault = isDefault;
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

  public UUID getCustomerId() {
    return customerId;
  }

  public void setCustomerId(UUID customerId) {
    this.customerId = customerId;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public String getRecipient() {
    return recipient;
  }

  public void setRecipient(String recipient) {
    this.recipient = recipient;
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

  public boolean isDefault() {
    return isDefault;
  }

  public void setDefault(boolean isDefault) {
    this.isDefault = isDefault;
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
    return "CustomerAddress{id="
        + id
        + ", orgId="
        + orgId
        + ", customerId="
        + customerId
        + ", isDefault="
        + isDefault
        + "}";
  }
}
