package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Org;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class OrgResponse {
  private UUID id;
  private String name;
  private String slug;
  private boolean active;
  private BigDecimal refundApprovalThreshold;
  private Integer orderTtlMinutes;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  // Billing profile (V51) — nullable; omitted from JSON when null (ObjectMapper NON_NULL).
  private String legalName;
  private String taxRegistrationNumber;
  private String addressLine1;
  private String addressLine2;
  private String city;
  private String country;
  private String phone;
  private String contactEmail;
  private String logoObjectKey;

  // Storefront branding (V52) — nullable; omitted from JSON when null.
  private String themeColor;
  private String instapayHandle;
  private String paymentInstructions;
  private String defaultLocale;

  // Storefront sharing & SEO (V54) — nullable; omitted from JSON when null. The owner-facing
  // Settings → Storefront form re-hydrates these on load; without them meta fields don't repopulate
  // and the og-image preview can't tell a stored image from the logo fallback.
  private String metaTitle;
  private String metaDescription;
  private String ogImageObjectKey;

  private OrgResponse() {}

  public static OrgResponse from(Org o) {
    OrgResponse r = new OrgResponse();
    r.id = o.getId();
    r.name = o.getName();
    r.slug = o.getSlug();
    r.active = o.isActive();
    r.refundApprovalThreshold = o.getRefundApprovalThreshold();
    r.orderTtlMinutes = o.getOrderTtlMinutes();
    r.createdAt = o.getCreatedAt();
    r.updatedAt = o.getUpdatedAt();
    r.legalName = o.getLegalName();
    r.taxRegistrationNumber = o.getTaxRegistrationNumber();
    r.addressLine1 = o.getAddressLine1();
    r.addressLine2 = o.getAddressLine2();
    r.city = o.getCity();
    r.country = o.getCountry();
    r.phone = o.getPhone();
    r.contactEmail = o.getContactEmail();
    r.logoObjectKey = o.getLogoObjectKey();
    r.themeColor = o.getThemeColor();
    r.instapayHandle = o.getInstapayHandle();
    r.paymentInstructions = o.getPaymentInstructions();
    r.defaultLocale = o.getDefaultLocale();
    r.metaTitle = o.getMetaTitle();
    r.metaDescription = o.getMetaDescription();
    r.ogImageObjectKey = o.getOgImageObjectKey();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }

  public boolean isActive() {
    return active;
  }

  public BigDecimal getRefundApprovalThreshold() {
    return refundApprovalThreshold;
  }

  public Integer getOrderTtlMinutes() {
    return orderTtlMinutes;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public String getLegalName() {
    return legalName;
  }

  public String getTaxRegistrationNumber() {
    return taxRegistrationNumber;
  }

  public String getAddressLine1() {
    return addressLine1;
  }

  public String getAddressLine2() {
    return addressLine2;
  }

  public String getCity() {
    return city;
  }

  public String getCountry() {
    return country;
  }

  public String getPhone() {
    return phone;
  }

  public String getContactEmail() {
    return contactEmail;
  }

  public String getLogoObjectKey() {
    return logoObjectKey;
  }

  public String getThemeColor() {
    return themeColor;
  }

  public String getInstapayHandle() {
    return instapayHandle;
  }

  public String getPaymentInstructions() {
    return paymentInstructions;
  }

  public String getDefaultLocale() {
    return defaultLocale;
  }

  public String getMetaTitle() {
    return metaTitle;
  }

  public String getMetaDescription() {
    return metaDescription;
  }

  public String getOgImageObjectKey() {
    return ogImageObjectKey;
  }
}
