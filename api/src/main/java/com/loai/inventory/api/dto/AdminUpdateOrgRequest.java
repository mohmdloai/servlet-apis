package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * Body for {@code PATCH /api/admin/orgs/{orgId}} (admin-plane org edit, divergence #3). {@code
 * name} is required; the policy knobs are optional - a {@code null} leaves that knob unchanged.
 */
public class AdminUpdateOrgRequest {
  private String name;
  private BigDecimal refundApprovalThreshold;
  private Integer orderTtlMinutes;

  // Storefront branding (V52) — a null field leaves the stored value unchanged (merge).
  private String themeColor;
  private String instapayHandle;
  private String paymentInstructions;
  private String defaultLocale;

  // Storefront SEO & social metadata (V54, slice C2) — a null field leaves the stored value
  // unchanged (merge); a blank string clears it. Admin-plane parity with the OWNER PUT route.
  private String metaTitle;
  private String metaDescription;
  private String ogImageObjectKey;

  // Commerce money config (V68, roadmap item 5). Merge semantics: null = leave unchanged.
  private BigDecimal taxRate;
  private BigDecimal shippingFee;

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
