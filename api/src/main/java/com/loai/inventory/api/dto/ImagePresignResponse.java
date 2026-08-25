package com.loai.inventory.api.dto;

import com.loai.inventory.service.ImagePresign;

/** The wire shape of a presigned image upload slot ({@code POST …/presign}). */
public class ImagePresignResponse {
  private String uploadUrl;
  private String objectKey;
  private long expiresInSeconds;

  private ImagePresignResponse() {}

  public static ImagePresignResponse from(ImagePresign p) {
    ImagePresignResponse r = new ImagePresignResponse();
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
