package com.loai.inventory.api.dto;

/**
 * Body for a payment-proof upload presign (roadmap item 2) — {@code POST
 * /api/public/orders/{token}/payment-proof/presign} and its portal twin. Same shape as the other
 * presign requests: the client's {@code filename} + {@code content_type}. The org + order scope
 * (hence the object key) come from the resolved token/session, never the body.
 */
public class PaymentProofPresignRequest {
  private String filename;
  private String contentType;

  public PaymentProofPresignRequest() {}

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
