package com.loai.inventory.api.dto;

/**
 * Response for a payment-proof upload presign (roadmap item 2): the presigned PUT {@code
 * upload_url} the shopper uploads the screenshot bytes to, and the {@code object_key} to hand back
 * in the sibling {@code payment-claim} call as {@code proof_object_key}. Same shape as the
 * logo/banner presign responses.
 */
public class PaymentProofPresignResponse {
  private String uploadUrl;
  private String objectKey;
  private long expiresInSeconds;

  private PaymentProofPresignResponse() {}

  public static PaymentProofPresignResponse of(
      String uploadUrl, String objectKey, long expiresInSeconds) {
    PaymentProofPresignResponse r = new PaymentProofPresignResponse();
    r.uploadUrl = uploadUrl;
    r.objectKey = objectKey;
    r.expiresInSeconds = expiresInSeconds;
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
