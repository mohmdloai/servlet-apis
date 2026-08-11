package com.loai.inventory.service;

import com.loai.inventory.common.text.Locales;
import com.loai.inventory.domain.model.NotificationType;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a notification's channel-agnostic {@code title}/{@code body} from its {@link
 * NotificationType}, payload and the recipient's locale. Templates live in code (not the DB), per
 * the spec — org-level customization is a later slice. Each new wired {@code NotificationType} adds
 * a {@code case} here, and the switch is exhaustive, so a type without a template cannot compile.
 *
 * <p><b>Slice L — every string exists in both supported locales.</b> Rendering happens once, at
 * produce time, and the result is stored on the notification row, so the language is decided by the
 * recipient's resolved locale ({@code customer.locale} → {@code org.default_locale} → {@code ar})
 * rather than by anything at read time. That is also why there is no lazy/re-render path: an
 * already-sent email cannot change language, and the portal feed shows the same words the email
 * did.
 *
 * <p><b>Deliberately not fixed here: the voice.</b> {@code ORDER_PLACED} is the one type sent to
 * both the org's staff and the customer, and its wording is merchant-facing ("New order SO-…") for
 * both. The Arabic below is a faithful translation of that, wart included — splitting a template
 * per recipient type is a real improvement and a different change, and translating a wart is honest
 * where quietly rewriting customer-facing copy inside a localization slice would not be. See {@code
 * stories/localized_notification_templates.md} §Out.
 */
final class NotificationTemplates {

  /**
   * A rendered notification: the channel-agnostic title + body, plus the label for the email's
   * call-to-action anchor (different events want different verbs — view-order vs review-items).
   */
  record Rendered(String title, String body, String ctaLabel) {}

  private NotificationTemplates() {}

  // Call-to-action labels, per locale. The Arabic matches the storefront's own ar.json wording, so
  // an email's button and the page it lands on say the same thing.

  private static String ctaViewOrder(String locale) {
    return isArabic(locale) ? "عرض الطلب" : "View your order";
  }

  private static String ctaTrackOrder(String locale) {
    return isArabic(locale) ? "تتبع طلبك" : "Track your order";
  }

  private static String ctaReviewItems(String locale) {
    return isArabic(locale) ? "قيّم منتجاتك" : "Review your items";
  }

  private static String ctaCompletePayment(String locale) {
    return isArabic(locale) ? "أكمل الدفع" : "Complete your payment";
  }

  static Rendered render(NotificationType type, Map<String, Object> payload, String rawLocale) {
    // Apply the SAME floor emailHtml does. If the two disagreed, an unrecognised locale would
    // produce an English body inside an RTL Arabic wrapper — the body and its own direction
    // attribute contradicting each other. In production `NotificationService.resolveLocale` has
    // already resolved this to a supported value; the floor is for everything else.
    String locale = Locales.resolve(rawLocale, null);
    boolean ar = isArabic(locale);
    return switch (type) {
      case ORDER_PLACED -> {
        String orderNumber = str(payload, "order_number");
        yield ar
            ? new Rendered(
                "طلب جديد " + orderNumber,
                "تم إنشاء الطلب " + orderNumber + " وهو في انتظار الدفع.",
                ctaViewOrder(locale))
            : new Rendered(
                "New order " + orderNumber,
                "Order " + orderNumber + " was placed and is awaiting payment.",
                ctaViewOrder(locale));
      }
      case ORDER_PAID -> {
        String orderNumber = str(payload, "order_number");
        String amount = str(payload, "amount");
        String currency = str(payload, "currency");
        yield ar
            ? new Rendered(
                "تم استلام دفعة الطلب " + orderNumber,
                "استلمنا دفعتك بقيمة "
                    + amount
                    + " "
                    + currency
                    + " للطلب "
                    + orderNumber
                    + ". تم تأكيد طلبك.",
                ctaViewOrder(locale))
            : new Rendered(
                "Payment received for " + orderNumber,
                "We received your payment of "
                    + amount
                    + " "
                    + currency
                    + " for order "
                    + orderNumber
                    + ". Your order is confirmed.",
                ctaViewOrder(locale));
      }
      case COMMENT_REPLIED -> {
        String listingTitle = str(payload, "listing_title");
        yield ar
            ? new Rendered(
                "أجاب المتجر على سؤالك",
                "رد المتجر على سؤالك عن \"" + listingTitle + "\". افتح صفحة المنتج لقراءة الإجابة.",
                ctaViewOrder(locale))
            : new Rendered(
                "The store answered your question",
                "The store replied to your question on \""
                    + listingTitle
                    + "\". Open the product page to read the answer.",
                ctaViewOrder(locale));
      }
      case REVIEW_REQUESTED -> {
        String orderNumber = str(payload, "order_number");
        yield ar
            ? new Rendered(
                "كيف كان طلبك؟",
                "تم تسليم طلبك "
                    + orderNumber
                    + ". شارك رأيك مع باقي المتسوقين وقيّم المنتجات التي استلمتها.",
                ctaReviewItems(locale))
            : new Rendered(
                "How was your order?",
                "Your order "
                    + orderNumber
                    + " was delivered. Tell other shoppers how it went — rate the items you"
                    + " received.",
                ctaReviewItems(locale));
      }
      case ORDER_SHIPPED -> {
        String orderNumber = str(payload, "order_number");
        // Carrier and tracking are optional on `fulfillment` — name them only when the merchant
        // actually recorded them. A sentence ending in "with " or a bare "tracking: null" is worse
        // than no sentence, and the shopper still gets the fact that matters (it shipped).
        StringBuilder body =
            ar
                ? new StringBuilder("طلبك ").append(orderNumber).append(" في الطريق إليك.")
                : new StringBuilder("Your order ").append(orderNumber).append(" is on its way.");
        opt(payload, "carrier")
            .ifPresent(
                carrier ->
                    body.append(
                        ar
                            ? " تم تسليمه إلى " + carrier + "."
                            : " It was handed to " + carrier + "."));
        opt(payload, "tracking_number")
            .ifPresent(
                tracking ->
                    body.append(
                        ar
                            ? " رقم التتبع: " + tracking + "."
                            : " Tracking number: " + tracking + "."));
        yield new Rendered(
            ar ? "تم شحن الطلب " + orderNumber : "Order " + orderNumber + " has shipped",
            body.toString(),
            ctaTrackOrder(locale));
      }
      case ORDER_CANCELLED -> {
        String orderNumber = str(payload, "order_number");
        // A cancel records a refund OBLIGATION; the merchant executes the real transfer separately
        // (refund.md's two-step lifecycle). So this says a refund is on its way — never that money
        // has already been sent — and an order with no prepayment gets no money sentence at all.
        StringBuilder body =
            ar
                ? new StringBuilder("تم إلغاء طلبك ").append(orderNumber).append(".")
                : new StringBuilder("Your order ")
                    .append(orderNumber)
                    .append(" has been cancelled.");
        opt(payload, "refund_total")
            .ifPresent(
                total ->
                    body.append(
                        ar
                            ? " جارٍ رد مبلغ " + total + " " + str(payload, "currency") + " إليك."
                            : " A refund of "
                                + total
                                + " "
                                + str(payload, "currency")
                                + " is being processed back to you."));
        yield new Rendered(
            ar ? "تم إلغاء الطلب " + orderNumber : "Order " + orderNumber + " was cancelled",
            body.toString(),
            ctaViewOrder(locale));
      }
      case PAYMENT_NEEDS_ATTENTION -> {
        String orderNumber = str(payload, "order_number");
        String amount = str(payload, "amount");
        String outstanding = str(payload, "outstanding");
        String currency = str(payload, "currency");
        yield ar
            ? new Rendered(
                "الطلب " + orderNumber + " ما زال يحتاج " + outstanding + " " + currency,
                "استلمنا دفعتك بقيمة "
                    + amount
                    + " "
                    + currency
                    + " للطلب "
                    + orderNumber
                    + "، لكنها لا تغطي المبلغ الإجمالي. ما زال مطلوبًا "
                    + outstanding
                    + " "
                    + currency
                    + " — سنحتفظ بمنتجاتك محجوزة حتى يتم السداد.",
                ctaCompletePayment(locale))
            : new Rendered(
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
                ctaCompletePayment(locale));
      }
    };
  }

  private static boolean isArabic(String locale) {
    return Locales.ARABIC.equals(Locales.normalize(locale));
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
   *
   * <p>The wrapper carries {@code lang} and {@code dir} (slice L). Without them an Arabic body
   * renders left-to-right in most mail clients, which mangles punctuation and any Latin token
   * inside the sentence — an order number sitting mid-line is exactly that case, and it is in
   * almost every one of these messages.
   */
  static String emailHtml(
      String body, String linkUrl, String ctaLabel, String unsubscribeUrl, String locale) {
    String lang = Locales.resolve(locale, null);
    StringBuilder sb =
        new StringBuilder("<div lang=\"")
            .append(lang)
            .append("\" dir=\"")
            .append(Locales.direction(lang))
            .append("\">");
    sb.append("<p>").append(escape(body)).append("</p>");
    if (linkUrl != null && !linkUrl.isBlank()) {
      String label = (ctaLabel == null || ctaLabel.isBlank()) ? ctaViewOrder(lang) : ctaLabel;
      sb.append("<p><a href=\"")
          .append(escape(linkUrl))
          .append("\">")
          .append(escape(label))
          .append("</a></p>");
    }
    if (unsubscribeUrl != null && !unsubscribeUrl.isBlank()) {
      sb.append("<hr><p style=\"font-size:12px;color:#888\">")
          .append(
              Locales.ARABIC.equals(lang) ? "لا تريد هذه الرسائل؟ " : "Don't want these emails? ")
          .append("<a href=\"")
          .append(escape(unsubscribeUrl))
          .append("\">")
          .append(Locales.ARABIC.equals(lang) ? "إلغاء الاشتراك" : "Unsubscribe")
          .append("</a>.</p>");
    }
    return sb.append("</div>").toString();
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
