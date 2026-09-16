package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Supplier;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The supplier directory row and detail. Carries no money at all — which is what lets it keep the
 * {@code /customers} gates while every {@code /goods-receipts} route is MANAGER. {@code phone_e164}
 * stays internal (derived, and nothing renders it).
 */
public class SupplierResponse {
  private UUID id;
  private String name;
  private String phone;
  private String email;
  private String address;
  private String notes;
  private boolean active;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private SupplierResponse() {}

  public static SupplierResponse from(Supplier s) {
    SupplierResponse r = new SupplierResponse();
    r.id = s.getId();
    r.name = s.getName();
    r.phone = s.getPhone();
    r.email = s.getEmail();
    r.address = s.getAddress();
    r.notes = s.getNotes();
    r.active = s.isActive();
    r.createdAt = s.getCreatedAt();
    r.updatedAt = s.getUpdatedAt();
    return r;
  }

  /** The {@code {id, name}} stub a receipt row names its supplier by. */
  public static Ref ref(Supplier s) {
    return s == null ? null : new Ref(s.getId(), s.getName());
  }

  public record Ref(UUID id, String name) {}

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getPhone() {
    return phone;
  }

  public String getEmail() {
    return email;
  }

  public String getAddress() {
    return address;
  }

  public String getNotes() {
    return notes;
  }

  public boolean isActive() {
    return active;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
