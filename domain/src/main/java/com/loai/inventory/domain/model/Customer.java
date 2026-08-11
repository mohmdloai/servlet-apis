package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class Customer {
  private UUID id;
  private UUID orgId;
  private String email;
  private String name;
  private String phone;

  /**
   * The canonical E.164 rendering of {@link #phone} (V79), or null when what the customer typed
   * could not be understood as a dialable number — which is a suppressed channel, not an error.
   *
   * <p><b>Derived, never independently set.</b> Every write site computes it from {@code phone} via
   * {@code Phone.toE164}; there is no API that edits it on its own, because the two drifting apart
   * would mean the number a support agent reads back and the number a message is sent to are
   * different. {@code phone} stays exactly what was typed.
   */
  private String phoneE164;

  private String address;

  /**
   * Preferred content language ({@code ar}/{@code en}, V81), or null when we have never learned it
   * — in which case the org's {@code default_locale} decides. Learned at anonymous checkout, filled
   * once at portal checkout if still absent, and settable by the customer at {@code PATCH
   * /api/portal/me}. This is what makes a notification arrive in a language the shopper reads.
   */
  private String locale;

  private OffsetDateTime emailVerifiedAt;
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

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  public String getPhoneE164() {
    return phoneE164;
  }

  public void setPhoneE164(String phoneE164) {
    this.phoneE164 = phoneE164;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  /**
   * When the customer first proved ownership of {@link #email} via a portal OTP verify — a trust
   * signal, nullable until then. Never gates login; for display / future policy. (P1,
   * portal_auth_core.md.)
   */
  public OffsetDateTime getEmailVerifiedAt() {
    return emailVerifiedAt;
  }

  public void setEmailVerifiedAt(OffsetDateTime emailVerifiedAt) {
    this.emailVerifiedAt = emailVerifiedAt;
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
