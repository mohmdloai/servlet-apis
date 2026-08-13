package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class Org {
  private UUID id;
  private String name;
  private String slug;
  private boolean active;

  /**
   * Whether the store wants to be FOUND (V83) — a different question from {@code active}'s "can it
   * transact?". Platforms always have a window where a merchant is live but not ready for organic
   * traffic (still setting up branding/inventory/pricing), and some sellers (B2B/wholesale,
   * franchise terms) never want organic search at all. {@code false} removes the store from the
   * public store index, 404s its crawl feed, and noindexes its pages — the direct link keeps
   * working. Default {@code true}: opt-out, so no existing store's crawl presence vanished on
   * migration day.
   */
  private boolean discoverable = true;

  private BigDecimal refundApprovalThreshold;
  private int orderTtlMinutes;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  // Suspension stamp (V43). Written by setSuspension since the platform console's lifecycle slice
  // shipped, but never read back until stories/platform_tenant_states.md — so the reason an ADMIN
  // typed was write-only, and `active=false` could not be told apart from an org born inactive at
  // self-serve registration. Both are null unless an ADMIN suspended the org; reactivate clears
  // them. The nullness of suspendedAt is what OrgStatus partitions on.
  private OffsetDateTime suspendedAt;
  private String suspendedReason;

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

  // Storefront branding (V52) — the per-org identity the public storefront + checkout confirmation
  // render. All nullable; default_locale defaults to 'ar' at the DB. See
  // stories/storefront_org_profile.md (B1).
  private String themeColor;
  private String instapayHandle;
  private String paymentInstructions;
  private String defaultLocale;

  // Storefront SEO & social metadata (V54, slice C2) — how a pasted store link unfurls. All
  // nullable; the storefront's resolveSeo falls back (org name / localized default / logo-backed og
  // route) when unset. og_image_object_key is a service-guarded {orgId}/og/… key streamed by the
  // stable public og-image route. See stories/storefront_seo_metadata.md (C2).
  private String metaTitle;
  private String metaDescription;
  private String ogImageObjectKey;

  // Store commerce config (V68, roadmap item 5) — the per-org money knobs applied at order
  // placement: tax_rate is a fraction (0.1400 = 14%) stamped onto every order line; shipping_fee is
  // a flat per-order delivery fee for ONLINE/PHONE orders (never IN_STORE). Both NOT NULL DEFAULT 0
  // at the DB, so an unconfigured org keeps the historic zero-tax free-shipping math unchanged.
  private BigDecimal taxRate = BigDecimal.ZERO;
  private BigDecimal shippingFee = BigDecimal.ZERO;

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

  public boolean isDiscoverable() {
    return discoverable;
  }

  public void setDiscoverable(boolean discoverable) {
    this.discoverable = discoverable;
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

  /** When a platform ADMIN suspended this org; {@code null} unless {@link OrgStatus#SUSPENDED}. */
  public OffsetDateTime getSuspendedAt() {
    return suspendedAt;
  }

  public void setSuspendedAt(OffsetDateTime suspendedAt) {
    this.suspendedAt = suspendedAt;
  }

  /** The note the suspending ADMIN typed; {@code null} unless {@link OrgStatus#SUSPENDED}. */
  public String getSuspendedReason() {
    return suspendedReason;
  }

  public void setSuspendedReason(String suspendedReason) {
    this.suspendedReason = suspendedReason;
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

  public String getThemeColor() {
    return themeColor;
  }

  public void setThemeColor(String themeColor) {
    this.themeColor = themeColor;
  }

  public String getInstapayHandle() {
    return instapayHandle;
  }

  public void setInstapayHandle(String instapayHandle) {
    this.instapayHandle = instapayHandle;
  }

  public String getPaymentInstructions() {
    return paymentInstructions;
  }

  public void setPaymentInstructions(String paymentInstructions) {
    this.paymentInstructions = paymentInstructions;
  }

  public String getDefaultLocale() {
    return defaultLocale;
  }

  public void setDefaultLocale(String defaultLocale) {
    this.defaultLocale = defaultLocale;
  }

  public String getMetaTitle() {
    return metaTitle;
  }

  public void setMetaTitle(String metaTitle) {
    this.metaTitle = metaTitle;
  }

  public String getMetaDescription() {
    return metaDescription;
  }

  public void setMetaDescription(String metaDescription) {
    this.metaDescription = metaDescription;
  }

  public String getOgImageObjectKey() {
    return ogImageObjectKey;
  }

  public void setOgImageObjectKey(String ogImageObjectKey) {
    this.ogImageObjectKey = ogImageObjectKey;
  }

  /** The per-line tax fraction applied at placement (0 = untaxed; 0.1400 = 14%). Never null. */
  public BigDecimal getTaxRate() {
    return taxRate;
  }

  public void setTaxRate(BigDecimal taxRate) {
    this.taxRate = taxRate;
  }

  /** The flat per-order delivery fee for ONLINE/PHONE orders (0 = free shipping). Never null. */
  public BigDecimal getShippingFee() {
    return shippingFee;
  }

  public void setShippingFee(BigDecimal shippingFee) {
    this.shippingFee = shippingFee;
  }

  @Override
  public String toString() {
    return "Org{id=" + id + ", slug='" + slug + "'}";
  }
}
