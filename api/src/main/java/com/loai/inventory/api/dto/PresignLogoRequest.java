package com.loai.inventory.api.dto;

/** Body for {@code POST /api/orgs/{orgId}/logo/presign} — the storefront logo upload. */
public class PresignLogoRequest {
  private String filename;
  private String contentType;

  public PresignLogoRequest() {}

  public String getFilename() {
    return filename;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }
}
