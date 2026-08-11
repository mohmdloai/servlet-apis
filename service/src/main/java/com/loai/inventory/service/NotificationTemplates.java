package com.loai.inventory.service;

import com.loai.inventory.domain.model.NotificationType;
import java.util.Map;
import java.util.Optional;

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
      case ORDER_SHIPPED -> {
        String orderNumber = str(payload, "order_number");
        StringBuilder body =
            new StringBuilder("Your order ").append(orderNumber).append(" is on its way.");
        // Carrier and tracking are optional on `fulfillment` — name them only when the merchant
        // actually recorded them. A sentence ending in "with " or a bare "tracking: null" is worse
        // than no sentence, and the shopper still gets the fact that matters (it shipped).
        opt(payload, "carrier")
            .ifPresent(carrier -> body.append(" It was handed to ").append(carrier).append("."));
        opt(payload, "tracking_number")
            .ifPresent(tracking -> body.append(" Tracking number: ").append(tracking).append("."));
        yield new Rendered(
            "Order " + orderNumber + " has shipped", body.toString(), "Track your order");
      }
      case ORDER_CANCELLED -> {
        String orderNumber = str(payload, "order_number");
        StringBuilder body =
            new StringBuilder("Your order ").append(orderNumber).append(" has been cancelled.");
        // A cancel records a refund OBLIGATION; the merchant executes the real transfer separately
        // (refund.md's two-step lifecycle). So this says a refund is on its way — never that money
        // has already been sent — and an order with no prepayment gets no money sentence at all.
        opt(payload, "refund_total")
            .ifPresent(
                total ->
                    body.append(" A refund of ")
                        .append(total)
                        .append(" ")
                        .append(str(payload, "currency"))
                        .append(" is being processed back to you."));
        yield new Rendered(
            "Order " + orderNumber + " was cancelled", body.toString(), CTA_VIEW_ORDER);
      }
      case PAYMENT_NEEDS_ATTENTION -> {
        String orderNumber = str(payload, "order_number");
        String amount = str(payload, "amount");
        String outstanding = str(payload, "outstanding");
        String currency = str(payload, "currency");
        yield new Rendered(
            "Order " + orderNumber + " still needs " + outstanding + " " + currency,
            "We received your payment of "
                + amount
                + " "
                + currency
                + " for order "
                + orderNumber
                + ", but it doesn't cover the total yet. "
                + outstanding
                + " "
                + currency
                + " is still outstanding — your items stay reserved until it's paid.",
            "Complete your payment");
      }
    };
  }

  private static String str(Map<String, Object> payload, String key) {
    Object v = payload == null ? null : payload.get(key);
    return v == null ? "" : v.toString();
  }

  /**
   * A payload value that may legitimately be absent, as a present-and-non-blank {@link Optional} —
   * the read for every field a template mentions only conditionally. {@link #str} deliberately
   * collapses missing to {@code ""}, which is right for a required field (it can't render "null"
   * mid-sentence) but useless for deciding whether to write the sentence at all.
   */
  private static Optional<String> opt(Map<String, Object> payload, String key) {
    Object v = payload == null ? null : payload.get(key);
    if (v == null) {
      return Optional.empty();
    }
    String s = v.toString().strip();
    return s.isEmpty() ? Optional.empty() : Optional.of(s);
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
