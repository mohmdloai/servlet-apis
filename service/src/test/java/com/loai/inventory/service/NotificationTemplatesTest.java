package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.NotificationType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rendering of the channel-agnostic templates + the email CTA-label wiring. */
class NotificationTemplatesTest {

  @Test
  void reviewRequested_rendersTitleBodyAndReviewCta() {
    NotificationTemplates.Rendered r =
        NotificationTemplates.render(
            NotificationType.REVIEW_REQUESTED, Map.of("order_number", "SO-000123"));

    assertEquals("How was your order?", r.title());
    assertTrue(r.body().contains("SO-000123"), "body names the order");
    assertTrue(r.body().toLowerCase().contains("rate"), "body invites a rating");
    assertEquals("Review your items", r.ctaLabel());
  }

  @Test
  void orderCentricTypes_keepTheViewOrderCta() {
    assertEquals(
        "View your order",
        NotificationTemplates.render(NotificationType.ORDER_PLACED, Map.of("order_number", "SO-1"))
            .ctaLabel());
    assertEquals(
        "View your order",
        NotificationTemplates.render(
                NotificationType.ORDER_PAID,
                Map.of("order_number", "SO-1", "amount", "20.00", "currency", "EGP"))
            .ctaLabel());
  }

  @Test
  void emailHtml_usesSuppliedCtaLabel_overTheDefault() {
    String html =
        NotificationTemplates.emailHtml(
            "Your order was delivered.",
            "https://shop.example/ar/acme/orders/tok",
            "Review your items",
            null);

    assertTrue(html.contains(">Review your items</a>"), "CTA anchor uses the supplied label");
    assertTrue(html.contains("https://shop.example/ar/acme/orders/tok"), "CTA links to the order");
  }

  @Test
  void emailHtml_blankCtaLabel_fallsBackToViewOrder() {
    String html = NotificationTemplates.emailHtml("Body.", "https://x/y", "  ", null);
    assertTrue(html.contains(">View your order</a>"));
  }
}
