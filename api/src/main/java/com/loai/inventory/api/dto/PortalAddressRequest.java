package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/portal/addresses} and {@code PATCH /api/portal/addresses/{id}} (slice
 * P4, {@code stories/portal_addresses_reorder.md}). {@code address} is required; the rest are
 * optional. {@code is_default} promotes this address to the customer's default (setting a new
 * default in {@code /default} is the other lever). The owning {@code (org, customer)} is never in
 * the body — it comes from the session token.
 */
public class PortalAddressRequest {
  private String label;
  private String recipient;
  private String phone;
  private String address;
  private Boolean isDefault;

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

  public Boolean getIsDefault() {
    return isDefault;
  }

  public void setIsDefault(Boolean isDefault) {
    this.isDefault = isDefault;
  }
}
