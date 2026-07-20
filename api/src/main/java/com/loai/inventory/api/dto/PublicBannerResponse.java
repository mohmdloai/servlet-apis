package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.PublicBannerView;

/**
 * A public home-banner row for the anonymous storefront ({@code GET /api/public/{orgSlug}/banners},
 * customization epic slice C1). Whitelisted: {@code headline}/{@code subheading} are resolved
 * server-side to a <b>single</b> value by {@code ?locale=} (slice L4 — reverses the shipped
 * both-locales-client-resolve), the image is a short-lived presigned GET URL (absent → gradient
 * slide), and the target is the structured slug pair. The global ObjectMapper omits null fields, so
 * absent copy / image simply drop out. Carries <b>no</b> id, org id, object key, window, or
 * timestamps.
 */
public class PublicBannerResponse {

  private String headline;
  private String subheading;
  private String imageUrl;
  private String targetType;
  private String targetSlug;

  private PublicBannerResponse() {}

  public static PublicBannerResponse from(PublicBannerView v) {
    PublicBannerResponse r = new PublicBannerResponse();
    r.headline = v.headline();
    r.subheading = v.subheading();
    r.imageUrl = v.imageUrl();
    r.targetType = v.targetType();
    r.targetSlug = v.targetSlug();
    return r;
  }

  public String getHeadline() {
    return headline;
  }

  public String getSubheading() {
    return subheading;
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
