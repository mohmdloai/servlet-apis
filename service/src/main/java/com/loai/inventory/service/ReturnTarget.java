package com.loai.inventory.service;

/**
 * Where Paymob sends the shopper back after the hosted checkout ({@code
 * stories/paymob_portal_pay.md}) — the one thing the two doors to {@link PaymentIntentService#pay}
 * must not share.
 *
 * <p>A guest holds an order-view magic token and returns to the branded tracker at that token; a
 * signed-in customer holds a session and returns to their own account order page, where the account
 * chrome and the way back to "my orders" are. Both pages carry the return watcher, so the payment
 * settles identically; only the page differs. The intent row does not record which door minted it —
 * the return URL is baked into Paymob's intention and nothing on our side reads it back.
 *
 * <p>Locale resolution is the caller's ({@code Locales.resolve(customer, org default)}), applied in
 * one place; the target only knows its own path shape.
 */
public sealed interface ReturnTarget {

  /** The branded status page at the shopper's raw order-view token — today's URL, unchanged. */
  static ReturnTarget publicTracker(String rawToken) {
    return new PublicTracker(rawToken);
  }

  /** The signed-in customer's own order page, by human-readable order number. */
  static ReturnTarget portalOrder(String orderNumber) {
    return new PortalOrder(orderNumber);
  }

  /** The storefront path (no origin) for {@code locale} and the org's {@code slug}. */
  String path(String locale, String slug);

  record PublicTracker(String rawToken) implements ReturnTarget {
    @Override
    public String path(String locale, String slug) {
      return "/" + locale + "/" + slug + "/orders/" + rawToken;
    }
  }

  record PortalOrder(String orderNumber) implements ReturnTarget {
    @Override
    public String path(String locale, String slug) {
      return "/" + locale + "/" + slug + "/account/orders/" + orderNumber;
    }
  }
}
