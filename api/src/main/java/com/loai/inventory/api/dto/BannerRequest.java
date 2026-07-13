package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontBannerService.BannerInput;
import java.time.OffsetDateTime;

/**
 * Create/edit body for a storefront banner ({@code POST}/{@code PUT
 * /api/orgs/{orgId}/storefront/banners[/{id}]}, customization epic slice C1). On create the target
 * is required; on edit the service merges (a null field leaves the stored value unchanged — see
 * {@link BannerInput}). Timestamps are ISO-8601. Validation (default-locale headline, target
 * resolution, key prefix, active cap, window) is the service's.
 */
public class BannerRequest {

  private String headlineAr;
  private String headlineEn;
  private String subheadingAr;
  private String subheadingEn;
  private String imageObjectKey;
  private String targetType;
  private String targetSlug;
  private Boolean active;
  private OffsetDateTime startsAt;
  private OffsetDateTime endsAt;

  public BannerRequest() {}

  public BannerInput toInput() {
    return new BannerInput(
        headlineAr,
        headlineEn,
        subheadingAr,
        subheadingEn,
        imageObjectKey,
        targetType,
        targetSlug,
        active,
        startsAt,
        endsAt);
  }

  public void setHeadlineAr(String headlineAr) {
    this.headlineAr = headlineAr;
  }

  public void setHeadlineEn(String headlineEn) {
    this.headlineEn = headlineEn;
  }

  public void setSubheadingAr(String subheadingAr) {
    this.subheadingAr = subheadingAr;
  }

  public void setSubheadingEn(String subheadingEn) {
    this.subheadingEn = subheadingEn;
  }

  public void setImageObjectKey(String imageObjectKey) {
    this.imageObjectKey = imageObjectKey;
  }

  public void setTargetType(String targetType) {
    this.targetType = targetType;
  }

  public void setTargetSlug(String targetSlug) {
    this.targetSlug = targetSlug;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public void setStartsAt(OffsetDateTime startsAt) {
    this.startsAt = startsAt;
  }

  public void setEndsAt(OffsetDateTime endsAt) {
    this.endsAt = endsAt;
  }
}
