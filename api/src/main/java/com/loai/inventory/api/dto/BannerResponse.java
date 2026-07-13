package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.StorefrontBanner;
import com.loai.inventory.service.StorefrontBannerService.BannerView;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An admin-plane banner row ({@code /api/orgs/{orgId}/storefront/banners}, customization epic slice
 * C1). Unlike the public read it serializes <b>both</b> locales, the raw {@code image_object_key},
 * a fresh presigned preview {@code image_url}, and the full editing state (order, active, window,
 * timestamps) — everything the editor form needs.
 */
public class BannerResponse {

  private UUID id;
  private String headlineAr;
  private String headlineEn;
  private String subheadingAr;
  private String subheadingEn;
  private String imageObjectKey;
  private String imageUrl;
  private String targetType;
  private String targetSlug;
  private int sortOrder;
  private boolean active;
  private OffsetDateTime startsAt;
  private OffsetDateTime endsAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private BannerResponse() {}

  public static BannerResponse from(BannerView view) {
    StorefrontBanner b = view.banner();
    BannerResponse r = new BannerResponse();
    r.id = b.getId();
    r.headlineAr = b.getHeadlineAr();
    r.headlineEn = b.getHeadlineEn();
    r.subheadingAr = b.getSubheadingAr();
    r.subheadingEn = b.getSubheadingEn();
    r.imageObjectKey = b.getImageObjectKey();
    r.imageUrl = view.imageUrl();
    r.targetType = b.getTargetType().wire();
    r.targetSlug = b.getTargetSlug();
    r.sortOrder = b.getSortOrder();
    r.active = b.isActive();
    r.startsAt = b.getStartsAt();
    r.endsAt = b.getEndsAt();
    r.createdAt = b.getCreatedAt();
    r.updatedAt = b.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
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

  public String getImageObjectKey() {
    return imageObjectKey;
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

  public int getSortOrder() {
    return sortOrder;
  }

  public boolean isActive() {
    return active;
  }

  public OffsetDateTime getStartsAt() {
    return startsAt;
  }

  public OffsetDateTime getEndsAt() {
    return endsAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
