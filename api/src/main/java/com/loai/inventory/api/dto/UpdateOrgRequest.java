package com.loai.inventory.api.dto;

import java.math.BigDecimal;

public class UpdateOrgRequest {
  private String name;
  private BigDecimal refundApprovalThreshold;
  private Integer orderTtlMinutes;

  // Billing profile (V51). A null field leaves the stored value unchanged (merge); a blank string
  // clears it. See stories/document_pdf_rendering.md (Part A).
  private String legalName;
  private String taxRegistrationNumber;
  private String addressLine1;
  private String addressLine2;
  private String city;
  private String country;
  private String phone;
  private String contactEmail;
  private String logoObjectKey;

  // Storefront branding (V52). A null field leaves the stored value unchanged (merge). See
  // stories/storefront_org_profile.md (B1).
  private String themeColor;
  private String instapayHandle;
  private String paymentInstructions;
  private String defaultLocale;

  // Storefront SEO & social metadata (V54, slice C2). A null field leaves the stored value
  // unchanged (merge); a blank string clears it. See stories/storefront_seo_metadata.md.
  private String metaTitle;
  private String metaDescription;
  private String ogImageObjectKey;

  /**
   * V83 merchant opt-out from search discovery. {@code null} = leave unchanged (merge-PUT); {@code
   * false} = the store can transact but must not be found via organic search.
   */
  private Boolean discoverable;

  // Commerce money config (V68, roadmap item 5). Merge semantics: null = leave unchanged.
  private BigDecimal taxRate;
  private BigDecimal shippingFee;

  public UpdateOrgRequest() {}

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public BigDecimal getRefundApprovalThreshold() {
    return refundApprovalThreshold;
  }

  public void setRefundApprovalThreshold(BigDecimal refundApprovalThreshold) {
    this.refundApprovalThreshold = refundApprovalThreshold;
  }

  public Integer getOrderTtlMinutes() {
    return orderTtlMinutes;
  }

  public void setOrderTtlMinutes(Integer orderTtlMinutes) {
    this.orderTtlMinutes = orderTtlMinutes;
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

  public Boolean getDiscoverable() {
    return discoverable;
  }

  public void setDiscoverable(Boolean discoverable) {
    this.discoverable = discoverable;
  }

  public BigDecimal getTaxRate() {
    return taxRate;
  }

  public void setTaxRate(BigDecimal taxRate) {
    this.taxRate = taxRate;
  }

  public BigDecimal getShippingFee() {
    return shippingFee;
  }

  public void setShippingFee(BigDecimal shippingFee) {
    this.shippingFee = shippingFee;
  }
}
