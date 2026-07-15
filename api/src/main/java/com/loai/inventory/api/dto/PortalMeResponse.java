package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Customer;

/**
 * The logged-in customer's own profile ({@code GET|PATCH /api/portal/me}). No internal ids, no org
 * id — just what the account page shows. {@code email_verified} is a display trust signal (true
 * once the first OTP verify stamped {@code email_verified_at}); email itself is read-only here.
 */
public class PortalMeResponse {
  private String name;
  private String email;
  private String phone;
  private String address;
  private boolean emailVerified;

  public PortalMeResponse() {}

  public PortalMeResponse(
      String name, String email, String phone, String address, boolean emailVerified) {
    this.name = name;
    this.email = email;
    this.phone = phone;
    this.address = address;
    this.emailVerified = emailVerified;
  }

  public static PortalMeResponse from(Customer c) {
    return new PortalMeResponse(
        c.getName(), c.getEmail(), c.getPhone(), c.getAddress(), c.getEmailVerifiedAt() != null);
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public String getPhone() {
    return phone;
  }

  public String getAddress() {
    return address;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }
}
