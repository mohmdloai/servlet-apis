package com.loai.inventory.common.storage;

import java.net.URLConnection;
import java.time.Duration;
import java.util.UUID;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Thin wrapper over an S3 {@link S3Presigner} for product-listing images. Presigning is an offline
 * operation (an HMAC over the request) — this class never talks to the object store, so it adds no
 * network latency and needs no S3 HTTP client.
 *
 * <p>Built once in {@code AppConfig} (like {@code DataSourceFactory}) and closed on shutdown.
 */
public final class ObjectStorage implements AutoCloseable {

  private final S3Presigner presigner;
  private final String bucket;
  private final Duration presignTtl;

  public ObjectStorage(S3Presigner presigner, String bucket, Duration presignTtl) {
    this.presigner = presigner;
    this.bucket = bucket;
    this.presignTtl = presignTtl;
  }

  /**
   * A tenant- and listing-scoped object key: {@code {orgId}/listings/{listingId}/{uuid}-{name}}.
   * The prefix lets the service verify on attach that an uploaded key really belongs to this org
   * and listing (cross-tenant attach guard).
   */
  public String newImageKey(UUID orgId, UUID listingId, String filename) {
    return orgId + "/listings/" + listingId + "/" + UUID.randomUUID() + "-" + sanitize(filename);
  }

  /** The key prefix every image of this org+listing must start with. */
  public static String keyPrefix(UUID orgId, UUID listingId) {
    return orgId + "/listings/" + listingId + "/";
  }

  /**
   * A tenant-scoped storefront-logo object key: {@code {orgId}/logo/{uuid}-{name}}. The prefix lets
   * the org-update path verify on attach that the key really belongs to this org (cross-tenant
   * guard, mirroring listing images).
   */
  public String newLogoKey(UUID orgId, String filename) {
    return orgId + "/logo/" + UUID.randomUUID() + "-" + sanitize(filename);
  }

  /** The key prefix every logo object of this org must start with. */
  public static String logoKeyPrefix(UUID orgId) {
    return orgId + "/logo/";
  }

  /**
   * A tenant-scoped storefront-banner object key: {@code {orgId}/banner/{uuid}-{name}}
   * (customization epic §4). The prefix lets the banner-attach path verify the key really belongs
   * to this org (cross-tenant guard, mirroring logo + listing images).
   */
  public String newBannerKey(UUID orgId, String filename) {
    return orgId + "/banner/" + UUID.randomUUID() + "-" + sanitize(filename);
  }

  /** The key prefix every banner object of this org must start with. */
  public static String bannerKeyPrefix(UUID orgId) {
    return orgId + "/banner/";
  }

  /**
   * A tenant-scoped storefront og-image object key: {@code {orgId}/og/{uuid}-{name}} (customization
   * epic §4, slice C2). The prefix lets the org-update path verify on attach that the key really
   * belongs to this org (cross-tenant guard, mirroring logo + banner + listing images).
   */
  public String newOgImageKey(UUID orgId, String filename) {
    return orgId + "/og/" + UUID.randomUUID() + "-" + sanitize(filename);
  }

  /** The key prefix every og-image object of this org must start with. */
  public static String ogImageKeyPrefix(UUID orgId) {
    return orgId + "/og/";
  }

  /**
   * A tenant- and order-scoped payment-proof object key: {@code
   * {orgId}/payment-proof/{orderId}/{uuid}-{name}} (roadmap item 2). The prefix lets the claim path
   * verify on attach that an anonymous shopper's key really belongs to this org + order — an
   * unauthenticated caller must not attach an arbitrary/cross-tenant key.
   */
  public String newPaymentProofKey(UUID orgId, UUID orderId, String filename) {
    return orgId + "/payment-proof/" + orderId + "/" + UUID.randomUUID() + "-" + sanitize(filename);
  }

  /** The key prefix every payment-proof object of this org+order must start with. */
  public static String paymentProofKeyPrefix(UUID orgId, UUID orderId) {
    return orgId + "/payment-proof/" + orderId + "/";
  }

  /**
   * The org-wide half of {@link #paymentProofKeyPrefix} — the tenant check the staff <b>read</b>
   * path applies before presigning a stored key. The read cannot use the order-scoped form: an
   * ORPHAN transaction is bound to no order, yet its proof is still the shopper's to show.
   */
  public static String paymentProofOrgPrefix(UUID orgId) {
    return orgId + "/payment-proof/";
  }

  /** A presigned PUT URL the client uploads bytes to directly. */
  public String presignPut(String objectKey, String contentType) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentTypeOrGuess(contentType, objectKey))
            .build();
    PutObjectPresignRequest presign =
        PutObjectPresignRequest.builder()
            .signatureDuration(presignTtl)
            .putObjectRequest(put)
            .build();
    return presigner.presignPutObject(presign).url().toString();
  }

  /** A short-lived presigned GET URL for displaying a stored image. */
  public String presignGet(String objectKey) {
    GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
    GetObjectPresignRequest presign =
        GetObjectPresignRequest.builder()
            .signatureDuration(presignTtl)
            .getObjectRequest(get)
            .build();
    return presigner.presignGetObject(presign).url().toString();
  }

  public long presignTtlSeconds() {
    return presignTtl.toSeconds();
  }

  private static String contentTypeOrGuess(String contentType, String objectKey) {
    if (contentType != null && !contentType.isBlank()) {
      return contentType;
    }
    String guessed = URLConnection.guessContentTypeFromName(objectKey);
    return guessed == null ? "application/octet-stream" : guessed;
  }

  private static String sanitize(String filename) {
    if (filename == null || filename.isBlank()) {
      return "file";
    }
    // Keep the basename only and restrict to a safe charset; the random UUID guarantees uniqueness.
    String base = filename.substring(filename.lastIndexOf('/') + 1);
    base = base.substring(base.lastIndexOf('\\') + 1);
    String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_");
    return cleaned.isBlank() ? "file" : cleaned;
  }

  @Override
  public void close() {
    presigner.close();
  }
}
