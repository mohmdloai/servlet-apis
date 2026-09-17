package com.loai.inventory.service.storefront;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the application's {@link StorefrontRevalidator}. The {@code WebPushSenderFactory} rule:
 * URL or secret unset → {@link StorefrontRevalidator#NOOP}, and the app boots exactly as before.
 */
public final class StorefrontRevalidatorFactory {

  private static final Logger log = LoggerFactory.getLogger(StorefrontRevalidatorFactory.class);

  private StorefrontRevalidatorFactory() {}

  public static StorefrontRevalidator build(ObjectMapper mapper, String url, String secret) {
    if (url == null || url.isBlank() || secret == null || secret.isBlank()) {
      log.warn(
          "STOREFRONT_REVALIDATE_URL / STOREFRONT_REVALIDATE_SECRET not set — storefront caches"
              + " expire on their TTL only (a profile change can take five minutes to show)");
      return StorefrontRevalidator.NOOP;
    }
    log.info("Storefront revalidate hook → {}", url.strip());
    return new JdkStorefrontRevalidator(mapper, url.strip(), secret.strip());
  }
}
