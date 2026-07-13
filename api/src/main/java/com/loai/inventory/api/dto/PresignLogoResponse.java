package com.loai.inventory.api.dto;

import com.loai.inventory.service.OrgService.LogoPresign;

/**
 * Response for {@code POST /api/orgs/{orgId}/logo/presign}: a presigned PUT {@code upload_url} the
 * client uploads bytes to, and the {@code object_key} to hand back via {@code PUT
 * /api/orgs/{orgId}} as {@code logo_object_key}. See {@code stories/storefront_org_profile.md}.
 */
public class PresignLogoResponse {
  private String uploadUrl;
  private String objectKey;
  private long expiresInSeconds;

  private PresignLogoResponse() {}

  public static PresignLogoResponse from(LogoPresign p) {
    PresignLogoResponse r = new PresignLogoResponse();
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
