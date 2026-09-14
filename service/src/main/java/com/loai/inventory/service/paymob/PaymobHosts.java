package com.loai.inventory.service.paymob;

import java.time.ZoneId;
import java.util.Map;

/**
 * Paymob is regional and the host differs per country. {@code org_paymob_config.region} (V98) is
 * CHECK-constrained to the one region this map knows; widening both is the day an org onboards
 * elsewhere.
 */
public final class PaymobHosts {

  private static final Map<String, String> BY_REGION = Map.of("EGYPT", "https://accept.paymob.com");

  /**
   * The zone Paymob's naive timestamps are printed in. Its {@code obj.created_at} carries no offset
   * and is the merchant's local time (a real callback showed {@code 14:27:50} beside the MIGS
   * block's {@code 11:27Z}); reading it as UTC put the ledger's {@code occurred_at} three hours
   * late.
   */
  private static final Map<String, ZoneId> ZONE_BY_REGION =
      Map.of("EGYPT", ZoneId.of("Africa/Cairo"));

  private PaymobHosts() {}

  public static ZoneId zoneForRegion(String region) {
    ZoneId zone = ZONE_BY_REGION.get(region);
    if (zone == null) {
      throw new IllegalArgumentException("no Paymob zone for region " + region);
    }
    return zone;
  }

  public static String forRegion(String region) {
    String host = BY_REGION.get(region);
    if (host == null) {
      throw new IllegalArgumentException("no Paymob host for region " + region);
    }
    return host;
  }
}
