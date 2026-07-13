package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontBannerService.PresignResult;

/**
 * Response for {@code POST /api/orgs/{orgId}/storefront/banners/presign}: a presigned PUT URL, the
 * org-scoped {@code {orgId}/banner/…} object key to attach afterward, and the URL's lifetime.
 */
public class BannerPresignResponse {

  private String uploadUrl;
  private String objectKey;
  private long expiresInSeconds;

  private BannerPresignResponse() {}

  public static BannerPresignResponse from(PresignResult p) {
    BannerPresignResponse r = new BannerPresignResponse();
    r.uploadUrl = p.uploadUrl();
    r.objectKey = p.objectKey();
    r.expiresInSeconds = p.expiresInSeconds();
    return r;
  }

  public String getUploadUrl() {
    return uploadUrl;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public long getExpiresInSeconds() {
    return expiresInSeconds;
  }
}
