package com.loai.inventory.service;

import com.loai.inventory.domain.model.NotificationType;
import java.util.Map;

/**
 * Renders a notification's channel-agnostic {@code title}/{@code body} from its {@link
 * NotificationType} and payload. Templates live in code (not the DB), per the spec — org-level
 * customization is a later slice. Each new wired {@code NotificationType} adds a {@code case} here.
 */
final class NotificationTemplates {

  /** Default email call-to-action label, shared by the order-centric notifications. */
  private static final String CTA_VIEW_ORDER = "View your order";

  /**
   * A rendered notification: the channel-agnostic title + body, plus the label for the email's
   * call-to-action anchor (different events want different verbs — view-order vs review-items).
   */
  record Rendered(String title, String body, String ctaLabel) {}

  private NotificationTemplates() {}

  static Rendered render(NotificationType type, Map<String, Object> payload) {
    return switch (type) {
      case ORDER_PLACED -> {
        String orderNumber = str(payload, "order_number");
        yield new Rendered(
            "New order " + orderNumber,
            "Order " + orderNumber + " was placed and is awaiting payment.",
            CTA_VIEW_ORDER);
      }
      case ORDER_PAID -> {
        String orderNumber = str(payload, "order_number");
        String amount = str(payload, "amount");
        String currency = str(payload, "currency");
        yield new Rendered(
            "Payment received for " + orderNumber,
            "We received your payment of "
                + amount
                + " "
                + currency
                + " for order "
                + orderNumber
                + ". Your order is confirmed.",
            CTA_VIEW_ORDER);
      }
      case COMMENT_REPLIED -> {
        String listingTitle = str(payload, "listing_title");
        yield new Rendered(
            "The store answered your question",
            "The store replied to your question on \""
                + listingTitle
                + "\". Open the product page to read the answer.",
            CTA_VIEW_ORDER);
      }
      case REVIEW_REQUESTED -> {
        String orderNumber = str(payload, "order_number");
        yield new Rendered(
            "How was your order?",
            "Your order "
                + orderNumber
                + " was delivered. Tell other shoppers how it went — rate the items you received.",
            "Review your items");
      }
    };
  }

  private static String str(Map<String, Object> payload, String key) {
    Object v = payload == null ? null : payload.get(key);
    return v == null ? "" : v.toString();
  }

  /**
   * Wrap a channel-agnostic {@code body} into a minimal HTML email: the message, an optional
   * call-to-action link, and (when present) a footer unsubscribe link. Deliberately plain —
   * org-branded templates are a later slice.
   */
  static String emailHtml(String body, String linkUrl, String ctaLabel, String unsubscribeUrl) {
    StringBuilder sb = new StringBuilder("<p>").append(escape(body)).append("</p>");
    if (linkUrl != null && !linkUrl.isBlank()) {
      String label = (ctaLabel == null || ctaLabel.isBlank()) ? CTA_VIEW_ORDER : ctaLabel;
      sb.append("<p><a href=\"")
          .append(escape(linkUrl))
          .append("\">")
          .append(escape(label))
          .append("</a></p>");
    }
    if (unsubscribeUrl != null && !unsubscribeUrl.isBlank()) {
      sb.append("<hr><p style=\"font-size:12px;color:#888\">Don't want these emails? ")
          .append("<a href=\"")
          .append(escape(unsubscribeUrl))
          .append("\">Unsubscribe</a>.</p>");
    }
    return sb.toString();
  }

  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
