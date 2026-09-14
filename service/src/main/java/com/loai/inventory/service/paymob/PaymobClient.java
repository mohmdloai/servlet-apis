package com.loai.inventory.service.paymob;

import com.loai.inventory.domain.model.OrgPaymobConfig;
import java.util.List;

/**
 * The outbound half of the Paymob integration: create an intention ({@code POST /v1/intention/})
 * under a merchant's own secret key. One method today; slice 3 adds the transaction inquiry.
 *
 * <p>An interface so the ITs can drive the checkout flow with a canned answer while {@link
 * JdkPaymobClient} is exercised against a stub HTTP server on its own.
 */
public interface PaymobClient {

  /** One line on Paymob's hosted page. {@code amountCents} is the unit price in piastres. */
  record Item(String name, long amountCents, int quantity, String description) {}

  /** Paymob-required billing block. Nothing here is authenticated; it labels the receipt. */
  record Billing(String firstName, String lastName, String phoneNumber, String email) {}

  /** Everything the intention call needs, already in Paymob's units (piastres, seconds). */
  record IntentionRequest(
      long amountCents,
      String currency,
      int integrationId,
      String specialReference,
      String notificationUrl,
      String redirectionUrl,
      long expirationSeconds,
      List<Item> items,
      Billing billing,
      String orgId,
      String salesOrderId,
      String intentId) {}

  /**
   * Paymob's handles for the intention: its id (slice 3 inquires by it), the Paymob-side order id
   * (appears SIGNED in every callback as {@code obj.order.id}), and the per-intention client secret
   * the browser needs to open Unified Checkout.
   */
  record IntentionResult(String intentionId, String paymobOrderId, String clientSecret) {}

  /**
   * @param secretKey the merchant's decrypted secret key — decrypted at the moment of use, never
   *     held longer than this call
   * @throws com.loai.inventory.common.exception.UpstreamFailureException when Paymob does not
   *     answer, or answers anything but a usable intention. <b>Never retried here</b>: a retried
   *     intention is a second intention.
   */
  IntentionResult createIntention(OrgPaymobConfig config, String secretKey, IntentionRequest req);

  /** The Unified Checkout URL for an intention: the hosted page the shopper is sent to. */
  static String checkoutUrl(OrgPaymobConfig config, String clientSecret) {
    return PaymobHosts.forRegion(config.region())
        + "/unifiedcheckout/?publicKey="
        + config.publicKey()
        + "&clientSecret="
        + clientSecret;
  }
}
