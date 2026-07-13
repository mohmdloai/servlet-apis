package com.loai.inventory.service;

import java.util.Optional;

/**
 * Fetches an object's raw bytes + content type for the stable public og-image stream (slice C2,
 * {@code stories/storefront_seo_metadata.md}). An interface (with a test-friendly fake and the
 * production {@link PresignedOgImageSource}) so the fetch — a network hop to object storage — can
 * be driven deterministically in ITs without a live store, mirroring {@code
 * DocumentRenderService.LogoSource}.
 */
public interface OgImageSource {

  /** An object's bytes and its stored content type. */
  record Fetched(byte[] bytes, String contentType) {}

  /**
   * The object's bytes + content type, or {@link Optional#empty()} on any miss or failure — a
   * missing preview beats a hanging crawler (epic §6), so the caller maps empty to a 404.
   */
  Optional<Fetched> fetch(String objectKey);
}
