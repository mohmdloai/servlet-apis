package com.loai.inventory.api.dto;

import com.loai.inventory.service.ProductListingService.PresignResult;

public class PresignImageUploadResponse {
  private String uploadUrl;
  private String objectKey;
  private long expiresInSeconds;

  private PresignImageUploadResponse() {}

  public static PresignImageUploadResponse from(PresignResult p) {
    PresignImageUploadResponse r = new PresignImageUploadResponse();
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
