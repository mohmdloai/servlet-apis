package com.loai.inventory.api.dto;

import com.loai.inventory.service.PaymentIntentService.PayResult;
import java.time.OffsetDateTime;

/**
 * {@code POST /api/public/orders/{token}/pay} → 200: where to pay and until when ({@code
 * stories/paymob_card_checkout.md}). Customer-safe: the checkout URL carries the org's public key
 * and the intention's client secret — both meant for the browser — and nothing else leaves.
 */
public class PaymentIntentResponse {

  private String checkoutUrl;
  private OffsetDateTime expiresAt;

  private PaymentIntentResponse() {}

  public static PaymentIntentResponse from(PayResult r) {
    PaymentIntentResponse out = new PaymentIntentResponse();
    out.checkoutUrl = r.checkoutUrl();
    out.expiresAt = r.expiresAt();
    return out;
  }

  public String getCheckoutUrl() {
    return checkoutUrl;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }
}
