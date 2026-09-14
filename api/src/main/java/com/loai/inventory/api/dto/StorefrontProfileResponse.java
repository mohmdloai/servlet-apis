package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.StorefrontProfileView;
import java.util.List;

/**
 * Public, whitelisted storefront profile ({@code GET /api/public/{orgSlug}}). Intentionally carries
 * no internal org field — no id, owner, thresholds, {@code order_ttl_minutes}, {@code active}, or
 * timestamps. Null fields are omitted from JSON (ObjectMapper NON_NULL). See {@code
 * stories/storefront_org_profile.md}.
 */
public class StorefrontProfileResponse {
  private String name;
  private String slug;
  private String logoUrl;
  private String themeColor;
  private String defaultLocale;
  private List<String> supportedLocales;
  private String currency;
  private String instapayHandle;
  private String paymentInstructions;
  private String metaTitle;
  private String metaDescription;
  private String ogImageVersion;
  private java.math.BigDecimal taxRate;
  private java.math.BigDecimal shippingFee;

  /**
   * Whether this store has a live WhatsApp channel (slice B). A <b>primitive</b>, so it is always
   * on the wire: Jackson omits nulls, and a boxed Boolean would make absence mean "no WhatsApp",
   * which is indistinguishable from an older backend that never sent the field. The portal branches
   * on it to decide whether a WhatsApp opt-out is even a real setting — same both-states-explicit
   * reflex as {@code email_verified} on the admin user DTO.
   */
  private boolean whatsappEnabled;

  /**
   * The payment methods this store currently accepts ({@code stories/paymob_connect.md}). {@code
   * instapay} always present; {@code card} joins it iff the org has an ACTIVE Paymob connection.
   */
  private List<String> paymentMethods;

  /** V83: false = the storefront must noindex every page of this store. Always on the wire. */
  private boolean discoverable;

  private StorefrontProfileResponse() {}

  public static StorefrontProfileResponse from(StorefrontProfileView v) {
    StorefrontProfileResponse r = new StorefrontProfileResponse();
    r.name = v.name();
    r.slug = v.slug();
    r.logoUrl = v.logoUrl();
    r.themeColor = v.themeColor();
    r.defaultLocale = v.defaultLocale();
    r.supportedLocales = v.supportedLocales();
    r.currency = v.currency();
    r.instapayHandle = v.instapayHandle();
    r.paymentInstructions = v.paymentInstructions();
    r.metaTitle = v.metaTitle();
    r.metaDescription = v.metaDescription();
    r.ogImageVersion = v.ogImageVersion();
    r.taxRate = v.taxRate();
    r.shippingFee = v.shippingFee();
    r.whatsappEnabled = v.whatsappEnabled();
    r.paymentMethods = v.paymentMethods();
    r.discoverable = v.discoverable();
    return r;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }

  public String getLogoUrl() {
    return logoUrl;
  }

  public String getThemeColor() {
    return themeColor;
  }

  public String getDefaultLocale() {
    return defaultLocale;
  }

  public List<String> getSupportedLocales() {
    return supportedLocales;
  }

  public String getCurrency() {
    return currency;
  }

  public String getInstapayHandle() {
    return instapayHandle;
  }

  public String getPaymentInstructions() {
    return paymentInstructions;
  }

  public java.math.BigDecimal getTaxRate() {
    return taxRate;
  }

  public java.math.BigDecimal getShippingFee() {
    return shippingFee;
  }

  public boolean isWhatsappEnabled() {
    return whatsappEnabled;
  }

  public List<String> getPaymentMethods() {
    return paymentMethods;
  }

  public boolean isDiscoverable() {
    return discoverable;
  }

  public String getMetaTitle() {
    return metaTitle;
  }

  public String getMetaDescription() {
    return metaDescription;
  }

  public String getOgImageVersion() {
    return ogImageVersion;
  }
}
