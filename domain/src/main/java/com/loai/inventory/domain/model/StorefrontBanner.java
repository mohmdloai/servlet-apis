package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant-managed home banner (customization epic slice C1). Bilingual paired copy ({@code
 * *_ar}/{@code *_en}, epic §1 — the default-locale one required, service-enforced), an optional
 * org-scoped image ({@code {orgId}/banner/…} key; absent → a branded gradient slide), a structured
 * slug-keyed {@link BannerTargetType} target within the same org (epic §2), an ordering, an active
 * flag, and an optional display window. Carries <b>no</b> price/discount/countdown — a computed
 * deal is unrepresentable (epic §7). See {@code stories/storefront_banners.md}.
 */
public class StorefrontBanner {
  private UUID id;
  private UUID orgId;
  private String headlineAr;
  private String headlineEn;
  private String subheadingAr;
  private String subheadingEn;
  private String imageObjectKey;
  private BannerTargetType targetType;
  private String targetSlug;
  private int sortOrder;
  private boolean active;
  private OffsetDateTime startsAt;
  private OffsetDateTime endsAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public String getHeadlineAr() {
    return headlineAr;
  }

  public void setHeadlineAr(String headlineAr) {
    this.headlineAr = headlineAr;
  }

  public String getHeadlineEn() {
    return headlineEn;
  }

  public void setHeadlineEn(String headlineEn) {
    this.headlineEn = headlineEn;
  }

  public String getSubheadingAr() {
    return subheadingAr;
  }

  public void setSubheadingAr(String subheadingAr) {
    this.subheadingAr = subheadingAr;
  }

  public String getSubheadingEn() {
    return subheadingEn;
  }

  public void setSubheadingEn(String subheadingEn) {
    this.subheadingEn = subheadingEn;
  }

  public String getImageObjectKey() {
    return imageObjectKey;
  }

  public void setImageObjectKey(String imageObjectKey) {
    this.imageObjectKey = imageObjectKey;
  }

  public BannerTargetType getTargetType() {
    return targetType;
  }

  public void setTargetType(BannerTargetType targetType) {
    this.targetType = targetType;
  }

  public String getTargetSlug() {
    return targetSlug;
  }

  public void setTargetSlug(String targetSlug) {
    this.targetSlug = targetSlug;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public OffsetDateTime getStartsAt() {
    return startsAt;
  }

  public void setStartsAt(OffsetDateTime startsAt) {
    this.startsAt = startsAt;
  }

  public OffsetDateTime getEndsAt() {
    return endsAt;
  }

  public void setEndsAt(OffsetDateTime endsAt) {
    this.endsAt = endsAt;
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
}
