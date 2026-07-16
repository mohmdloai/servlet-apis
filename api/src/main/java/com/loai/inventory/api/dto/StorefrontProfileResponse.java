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
