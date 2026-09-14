package com.loai.inventory.service.paymob;

import java.util.Map;

/**
 * Paymob is regional and the host differs per country. {@code org_paymob_config.region} (V98) is
 * CHECK-constrained to the one region this map knows; widening both is the day an org onboards
 * elsewhere.
 */
public final class PaymobHosts {

  private static final Map<String, String> BY_REGION = Map.of("EGYPT", "https://accept.paymob.com");

  private PaymobHosts() {}

  public static String forRegion(String region) {
    String host = BY_REGION.get(region);
    if (host == null) {
      throw new IllegalArgumentException("no Paymob host for region " + region);
    }
    return host;
  }
}
