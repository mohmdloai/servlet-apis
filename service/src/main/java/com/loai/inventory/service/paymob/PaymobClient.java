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

  /**
   * Exchange the account's legacy API key for a short-lived auth token ({@code POST
   * /api/auth/tokens}). The transaction-inquiry endpoint accepts only this token — the secret key
   * is refused there (probed 2026-09-14). One exchange per org per sweep; the token is never
   * stored.
   *
   * @throws com.loai.inventory.common.exception.UpstreamFailureException when Paymob does not
   *     answer or rejects the key
   */
  String authenticate(OrgPaymobConfig config, String apiKey);

  /**
   * Ask Paymob for the transaction of one intention ({@code POST
   * /api/ecommerce/orders/transaction_inquiry}), by the Paymob-side order id when we have it (the
   * signed binding), else by our {@code special_reference} (Paymob's {@code merchant_order_id}).
   *
   * @return the bare transaction object as JSON, or empty when Paymob has no transaction for it yet
   *     — the shopper never reached the card form, or is still on it
   * @throws com.loai.inventory.common.exception.UpstreamFailureException on a transport failure or
   *     a rejected token — the sweep ends and every intent stays as it was
   */
  java.util.Optional<String> inquireTransaction(
      OrgPaymobConfig config, String authToken, String paymobOrderId, String merchantOrderId);

  /** The Unified Checkout URL for an intention: the hosted page the shopper is sent to. */
  static String checkoutUrl(OrgPaymobConfig config, String clientSecret) {
    return PaymobHosts.forRegion(config.region())
        + "/unifiedcheckout/?publicKey="
        + config.publicKey()
        + "&clientSecret="
        + clientSecret;
  }
}
