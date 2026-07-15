package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.CustomerAddress;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A saved address as returned by the portal address book (slice P4, {@code
 * stories/portal_addresses_reorder.md}). Carries the {@code id} the row edits/deletes/promotes
 * against, the content fields, and the {@code is_default} flag the UI reflects. No {@code org_id}
 * or {@code customer_id} crosses the boundary — the session already fixes the owner.
 */
public class PortalAddressResponse {
  private UUID id;
  private String label;
  private String recipient;
  private String phone;
  private String address;
  private boolean isDefault;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private PortalAddressResponse() {}

  public static PortalAddressResponse from(CustomerAddress a) {
    PortalAddressResponse r = new PortalAddressResponse();
    r.id = a.getId();
    r.label = a.getLabel();
    r.recipient = a.getRecipient();
    r.phone = a.getPhone();
    r.address = a.getAddress();
    r.isDefault = a.isDefault();
    r.createdAt = a.getCreatedAt();
    r.updatedAt = a.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getLabel() {
    return label;
  }

  public String getRecipient() {
    return recipient;
  }

  public String getPhone() {
    return phone;
  }

  public String getAddress() {
    return address;
  }

  public boolean getIsDefault() {
    return isDefault;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
