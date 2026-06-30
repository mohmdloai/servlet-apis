package com.loai.inventory.api.dto;

/** Body for {@code POST /product-listings/{id}/images/presign}. */
public class PresignImageUploadRequest {
  private String filename;
  private String contentType;

  public PresignImageUploadRequest() {}

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
