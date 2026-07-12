package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class Org {
  private UUID id;
  private String name;
  private String slug;
  private boolean active;
  private BigDecimal refundApprovalThreshold;
  private int orderTtlMinutes;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  // Billing profile (V51) — the seller identity a printable invoice/receipt header needs. All
  // nullable; an org with none set still renders a valid document. Set via setters (the 8-arg
  // constructor is deliberately unchanged to keep its callers compiling).
  private String legalName;
  private String taxRegistrationNumber;
  private String addressLine1;
  private String addressLine2;
  private String city;
  private String country;
  private String phone;
  private String contactEmail;
  private String logoObjectKey;

  public Org() {}

  public Org(
      UUID id,
      String name,
      String slug,
      boolean active,
      BigDecimal refundApprovalThreshold,
      int orderTtlMinutes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.name = name;
    this.slug = slug;
    this.active = active;
    this.refundApprovalThreshold = refundApprovalThreshold;
    this.orderTtlMinutes = orderTtlMinutes;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public BigDecimal getRefundApprovalThreshold() {
    return refundApprovalThreshold;
  }

  public void setRefundApprovalThreshold(BigDecimal refundApprovalThreshold) {
    this.refundApprovalThreshold = refundApprovalThreshold;
  }

  /** Payment-hold window in minutes for reserved online/phone orders (V47, default 1440). */
  public int getOrderTtlMinutes() {
    return orderTtlMinutes;
  }

  public void setOrderTtlMinutes(int orderTtlMinutes) {
    this.orderTtlMinutes = orderTtlMinutes;
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

  public String getLegalName() {
    return legalName;
  }

  public void setLegalName(String legalName) {
    this.legalName = legalName;
  }

  public String getTaxRegistrationNumber() {
    return taxRegistrationNumber;
  }

  public void setTaxRegistrationNumber(String taxRegistrationNumber) {
    this.taxRegistrationNumber = taxRegistrationNumber;
  }

  public String getAddressLine1() {
    return addressLine1;
  }

  public void setAddressLine1(String addressLine1) {
    this.addressLine1 = addressLine1;
  }

  public String getAddressLine2() {
    return addressLine2;
  }

  public void setAddressLine2(String addressLine2) {
    this.addressLine2 = addressLine2;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String country) {
    this.country = country;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getContactEmail() {
    return contactEmail;
  }

  public void setContactEmail(String contactEmail) {
    this.contactEmail = contactEmail;
  }

  public String getLogoObjectKey() {
    return logoObjectKey;
  }

  public void setLogoObjectKey(String logoObjectKey) {
    this.logoObjectKey = logoObjectKey;
  }

  @Override
  public String toString() {
    return "Org{id=" + id + ", slug='" + slug + "'}";
  }
}
