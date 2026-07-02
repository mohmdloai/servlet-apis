package com.loai.inventory.service;

import com.loai.inventory.domain.model.NotificationType;
import java.util.Map;

/**
 * Renders a notification's channel-agnostic {@code title}/{@code body} from its {@link
 * NotificationType} and payload. Templates live in code (not the DB), per the spec — org-level
 * customization is a later slice. Each new wired {@code NotificationType} adds a {@code case} here.
 */
final class NotificationTemplates {

  /** A rendered notification: the channel-agnostic title + body. */
  record Rendered(String title, String body) {}

  private NotificationTemplates() {}

  static Rendered render(NotificationType type, Map<String, Object> payload) {
    return switch (type) {
      case ORDER_PLACED -> {
        String orderNumber = str(payload, "order_number");
        yield new Rendered(
            "New order " + orderNumber,
            "Order " + orderNumber + " was placed and is awaiting payment.");
      }
    };
  }

  private static String str(Map<String, Object> payload, String key) {
    Object v = payload == null ? null : payload.get(key);
    return v == null ? "" : v.toString();
  }

  /**
   * Wrap a channel-agnostic {@code body} into a minimal HTML email, appending a call-to-action link
   * when {@code linkUrl} is present. Deliberately plain — org-branded templates are a later slice.
   */
  static String emailHtml(String body, String linkUrl) {
    StringBuilder sb = new StringBuilder("<p>").append(escape(body)).append("</p>");
    if (linkUrl != null && !linkUrl.isBlank()) {
      sb.append("<p><a href=\"").append(escape(linkUrl)).append("\">View your order</a></p>");
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
