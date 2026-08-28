package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.text.Locales;
import com.loai.inventory.domain.model.NotificationType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Rendering of the channel-agnostic templates + the email CTA-label wiring, in both locales. */
class NotificationTemplatesTest {

  private static final String EN = Locales.ENGLISH;
  private static final String AR = Locales.ARABIC;

  @Test
  void reviewRequested_rendersTitleBodyAndReviewCta() {
    NotificationTemplates.Rendered r =
        NotificationTemplates.render(
            NotificationType.REVIEW_REQUESTED, Map.of("order_number", "SO-000123"), EN);

    assertEquals("How was your order?", r.title());
    assertTrue(r.body().contains("SO-000123"), "body names the order");
    assertTrue(r.body().toLowerCase().contains("rate"), "body invites a rating");
    assertEquals("Review your items", r.ctaLabel());
  }

  @Test
  void orderCentricTypes_keepTheViewOrderCta() {
    assertEquals(
        "View your order",
        NotificationTemplates.render(
                NotificationType.ORDER_PLACED, Map.of("order_number", "SO-1"), EN)
            .ctaLabel());
    assertEquals(
        "View your order",
        NotificationTemplates.render(
                NotificationType.ORDER_PAID,
                Map.of("order_number", "SO-1", "amount", "20.00", "currency", "EGP"),
                EN)
            .ctaLabel());
  }

  // Slice L — the Arabic side

  /**
   * The contract that matters most: <b>every</b> type renders in Arabic, and none of it silently
   * falls through to the English string. A type added later without an Arabic branch fails here
   * rather than reaching a shopper.
   */
  @ParameterizedTest
  @EnumSource(NotificationType.class)
  void everyTypeRendersInArabic_withNoLatinLettersLeftInTheBody(NotificationType type) {
    NotificationTemplates.Rendered ar = NotificationTemplates.render(type, payloadFor(type), AR);

    assertTrue(containsArabic(ar.title()), type + " title is Arabic: " + ar.title());
    assertTrue(containsArabic(ar.body()), type + " body is Arabic: " + ar.body());
    assertTrue(containsArabic(ar.ctaLabel()), type + " CTA is Arabic: " + ar.ctaLabel());
    // The only Latin left in a body should be the interpolated data (order number, currency).
    assertFalse(
        ar.body().matches(".*\\b(order|payment|your|the)\\b.*"),
        type + " body leaked English: " + ar.body());
  }

  @ParameterizedTest
  @EnumSource(NotificationType.class)
  void everyTypeRendersDifferentlyPerLocale(NotificationType type) {
    Map<String, Object> payload = payloadFor(type);
    assertFalse(
        NotificationTemplates.render(type, payload, AR)
            .body()
            .equals(NotificationTemplates.render(type, payload, EN).body()),
        type + " renders the same body in both locales — a missing translation");
  }

  @Test
  void arabicShipped_namesCarrierAndTracking_onlyWhenPresent() {
    NotificationTemplates.Rendered withBoth =
        NotificationTemplates.render(
            NotificationType.ORDER_SHIPPED,
            Map.of("order_number", "SO-9", "carrier", "Bosta", "tracking_number", "EG-1"),
            AR);
    assertTrue(withBoth.body().contains("Bosta"));
    assertTrue(withBoth.body().contains("EG-1"));

    NotificationTemplates.Rendered bare =
        NotificationTemplates.render(
            NotificationType.ORDER_SHIPPED, Map.of("order_number", "SO-9"), AR);
    assertFalse(bare.body().contains("null"), "an absent optional never renders: " + bare.body());
    assertTrue(containsArabic(bare.body()));
  }

  @Test
  void anUnknownOrNullLocaleFallsBackToArabic_neverThrows() {
    // Locales.resolve's floor. notify() runs inside a business txn, so an odd stored value must
    // pick a language rather than roll back an order.
    assertTrue(
        containsArabic(
            NotificationTemplates.render(
                    NotificationType.ORDER_SHIPPED, Map.of("order_number", "SO-1"), null)
                .body()));
    assertTrue(
        containsArabic(
            NotificationTemplates.render(
                    NotificationType.ORDER_SHIPPED, Map.of("order_number", "SO-1"), "fr")
                .body()));
  }

  @Test
  void arabicEmail_declaresRtlAndTranslatesTheUnsubscribeFooter() {
    String html =
        NotificationTemplates.emailHtml(
            "طلبك في الطريق إليك.", "https://shop/ar/acme/orders/t", "تتبع طلبك", "https://u", AR);

    // Without dir=rtl most mail clients lay Arabic out left-to-right, which mangles the punctuation
    // and any Latin token inside the sentence — the order number is exactly that.
    assertTrue(html.contains("dir=\"rtl\""), html);
    assertTrue(html.contains("lang=\"ar\""), html);
    assertTrue(html.contains("إلغاء الاشتراك"), "the unsubscribe footer is Arabic too");
    assertFalse(html.contains("Unsubscribe"), "no English left in an Arabic email");
  }

  @Test
  void englishEmail_staysLtr() {
    String html = NotificationTemplates.emailHtml("Body.", "https://x/y", "Track", "https://u", EN);
    assertTrue(html.contains("dir=\"ltr\""));
    assertTrue(html.contains(">Unsubscribe</a>"));
  }

  @Test
  void emailHtml_usesSuppliedCtaLabel_overTheDefault() {
    String html =
        NotificationTemplates.emailHtml(
            "Your order was delivered.",
            "https://shop.example/ar/acme/orders/tok",
            "Review your items",
            null,
            EN);

    assertTrue(html.contains(">Review your items</a>"), "CTA anchor uses the supplied label");
    assertTrue(html.contains("https://shop.example/ar/acme/orders/tok"), "CTA links to the order");
  }

  @Test
  void emailHtml_blankCtaLabel_fallsBackToViewOrder() {
    String html = NotificationTemplates.emailHtml("Body.", "https://x/y", "  ", null, EN);
    assertTrue(html.contains(">View your order</a>"));
  }

  // helpers

  /** A payload carrying every key the given type's template reads. */
  private static Map<String, Object> payloadFor(NotificationType type) {
    return switch (type) {
      case ORDER_PAID -> Map.of("order_number", "SO-1", "amount", "20.00", "currency", "EGP");
      case PAYMENT_NEEDS_ATTENTION ->
          Map.of(
              "order_number", "SO-1",
              "amount", "20.00",
              "outstanding", "30.00",
              "currency", "EGP");
      case COMMENT_REPLIED -> Map.of("listing_title", "Widget");
      case ORDER_SHIPPED ->
          Map.of("order_number", "SO-1", "carrier", "Bosta", "tracking_number", "EG-1");
      case ORDER_CANCELLED ->
          Map.of("order_number", "SO-1", "refund_total", "20.00", "currency", "EGP");
      case PAYMENT_NOT_FOUND ->
          Map.of(
              "order_number", "SO-1",
              "reference", "770099887766",
              "reason", "NO_TRANSFER",
              "note", "nothing arrived",
              "held_until", "2026-07-14T09:12Z");
      case ORDER_PLACED, REVIEW_REQUESTED -> Map.of("order_number", "SO-1");
    };
  }

  private static boolean containsArabic(String s) {
    return s != null && s.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF);
  }
}
