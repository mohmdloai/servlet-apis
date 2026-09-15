package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Who the shop buys from (V102, {@code stories/supplier_goods_receipt.md}) — {@link Customer}'s
 * mirror: org-scoped, no password, no portal, no authentication ever.
 *
 * <p>Unique per {@code (org, fold_search(name))}, not per name: two spellings of one supplier are
 * one AP balance split in half. {@link #phoneE164} is derived from {@link #phone} at every write
 * (never set on its own), exactly as on a customer.
 */
public class Supplier {
  private UUID id;
  private UUID orgId;
  private String name;
  private String phone;
  private String phoneE164;
  private String email;
  private String address;
  private String notes;
  private boolean active = true;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Supplier() {}

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

  public String getPhoneE164() {
    return phoneE164;
  }

  public void setPhoneE164(String phoneE164) {
    this.phoneE164 = phoneE164;
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

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
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
    return "Supplier{id=" + id + ", orgId=" + orgId + ", name='" + name + "'}";
  }
}
