package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.PublicBannerView;

/**
 * A public home-banner row for the anonymous storefront ({@code GET /api/public/{orgSlug}/banners},
 * customization epic slice C1). Whitelisted: both locale columns cross verbatim (the client
 * resolves the fallback, epic §1), the image is a short-lived presigned GET URL (absent → gradient
 * slide), and the target is the structured slug pair. The global ObjectMapper omits null fields, so
 * absent copy / image simply drop out. Carries <b>no</b> id, org id, object key, window, or
 * timestamps.
 */
public class PublicBannerResponse {

  private String headlineAr;
  private String headlineEn;
  private String subheadingAr;
  private String subheadingEn;
  private String imageUrl;
  private String targetType;
  private String targetSlug;

  private PublicBannerResponse() {}

  public static PublicBannerResponse from(PublicBannerView v) {
    PublicBannerResponse r = new PublicBannerResponse();
    r.headlineAr = v.headlineAr();
    r.headlineEn = v.headlineEn();
    r.subheadingAr = v.subheadingAr();
    r.subheadingEn = v.subheadingEn();
    r.imageUrl = v.imageUrl();
    r.targetType = v.targetType();
    r.targetSlug = v.targetSlug();
    return r;
  }

  public String getHeadlineAr() {
    return headlineAr;
  }

  public String getHeadlineEn() {
    return headlineEn;
  }

  public String getSubheadingAr() {
    return subheadingAr;
  }

  public String getSubheadingEn() {
    return subheadingEn;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public String getTargetType() {
    return targetType;
  }

  public String getTargetSlug() {
    return targetSlug;
  }
}
