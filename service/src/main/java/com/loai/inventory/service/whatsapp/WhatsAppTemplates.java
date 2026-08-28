package com.loai.inventory.service.whatsapp;

import com.loai.inventory.common.text.Locales;
import com.loai.inventory.domain.model.NotificationType;
import java.util.List;
import java.util.Map;

/**
 * Maps a {@link NotificationType} + payload to the <b>template invocation</b> WhatsApp requires.
 *
 * <p><b>Why this is not just {@code NotificationTemplates}.</b> The in-app feed and the email carry
 * a rendered sentence this codebase writes. WhatsApp does not accept one: a business-initiated
 * message must name a template Meta has already approved and supply ordered positional parameters
 * for it. So the same event is expressed twice — as prose there, as {@code (name, language,
 * params)} here — and the two cannot be derived from each other.
 *
 * <p><b>The names below are a contract with Meta, not with this code.</b> Each must be registered
 * and approved in the merchant's WhatsApp Manager as a <em>utility</em> template, in <em>both</em>
 * languages (Meta approves per language), with a body whose {@code {{1}}, {{2}}, …} placeholders
 * line up exactly with {@link Spec#params} below. The reference bodies are documented per type so
 * that whoever does the registration has the exact text; if a merchant's approved body has a
 * different parameter count, that merchant's sends fail with a terminal 4xx and the delivery is
 * marked FAILED rather than retried forever.
 *
 * <p><b>Not every notification type gets a template.</b> Only the order-lifecycle events a shopper
 * needs off-app are here; {@code COMMENT_REPLIED} and {@code REVIEW_REQUESTED} are deliberately
 * absent — a Q&amp;A answer and a review nudge are not utility messages, Meta would classify them
 * as marketing, and a paid marketing template for "rate your items" is not what this epic is for. A
 * type with no spec simply gets no WhatsApp leg, which {@code channelsFor} treats as a suppressed
 * channel exactly like a customer with no phone number.
 */
public final class WhatsAppTemplates {

  private WhatsAppTemplates() {}

  /** A resolved invocation: which approved template, in which language, with which parameters. */
  public record Spec(String name, String language, List<String> params) {}

  /**
   * The approved template names. Kept as constants because they are external identifiers — a typo
   * here is a terminal provider rejection at send time, not a compile error.
   */
  public static final String ORDER_PAID = "order_paid";

  public static final String ORDER_SHIPPED = "order_shipped";
  public static final String ORDER_CANCELLED = "order_cancelled";
  public static final String PAYMENT_NEEDS_ATTENTION = "payment_needs_attention";
  public static final String ORDER_PLACED = "order_placed";

  /**
   * The invocation for {@code type}, or {@code null} when this type has no WhatsApp template (see
   * the class note — that is a suppressed channel, not an error).
   *
   * <p>Reference bodies to register with Meta, {@code {{n}}} matching the params in order:
   *
   * <ul>
   *   <li>{@code order_placed} — en: "We received your order {{1}}. We'll confirm it once payment
   *       arrives." · ar: "استلمنا طلبك {{1}}. سنؤكده بمجرد وصول الدفع."
   *   <li>{@code order_paid} — en: "Payment received for order {{1}}. Your order is confirmed." ·
   *       ar: "تم استلام دفعة الطلب {{1}}. تم تأكيد طلبك."
   *   <li>{@code order_shipped} — en: "Your order {{1}} is on its way." · ar: "طلبك {{1}} في الطريق
   *       إليك."
   *   <li>{@code order_cancelled} — en: "Your order {{1}} has been cancelled." · ar: "تم إلغاء طلبك
   *       {{1}}."
   *   <li>{@code payment_needs_attention} — en: "We received {{2}} for order {{1}}, but {{3}} is
   *       still outstanding." · ar: "استلمنا {{2}} للطلب {{1}}، وما زال مطلوبًا {{3}}."
   * </ul>
   *
   * <p>Deliberately <b>fewer parameters than the email says</b>: carrier and tracking are omitted
   * from {@code order_shipped} because they are optional on the order, and a Meta template's
   * parameter count is fixed at approval — a template that names a carrier cannot be sent for a
   * shipment that has none. The WhatsApp message carries the fact and the link-out; the email
   * carries the detail.
   */
  public static Spec specFor(NotificationType type, Map<String, Object> payload, String locale) {
    String language = Locales.resolve(locale, null);
    String orderNumber = str(payload, "order_number");
    return switch (type) {
      case ORDER_PLACED -> new Spec(ORDER_PLACED, language, List.of(orderNumber));
      case ORDER_PAID -> new Spec(ORDER_PAID, language, List.of(orderNumber));
      case ORDER_SHIPPED -> new Spec(ORDER_SHIPPED, language, List.of(orderNumber));
      case ORDER_CANCELLED -> new Spec(ORDER_CANCELLED, language, List.of(orderNumber));
      case PAYMENT_NEEDS_ATTENTION ->
          new Spec(
              PAYMENT_NEEDS_ATTENTION,
              language,
              List.of(orderNumber, money(payload, "amount"), money(payload, "outstanding")));
      // Not utility messages — see the class note. PAYMENT_NOT_FOUND is a utility message in
      // spirit ("check your reference"), but it has no approved Meta template yet — the email +
      // feed carry it (stories/payment_claim_not_found.md); a template is a later, approved add.
      case COMMENT_REPLIED, REVIEW_REQUESTED, PAYMENT_NOT_FOUND -> null;
    };
  }

  private static String money(Map<String, Object> payload, String key) {
    String amount = str(payload, key);
    String currency = str(payload, "currency");
    return currency.isEmpty() ? amount : amount + " " + currency;
  }

  private static String str(Map<String, Object> payload, String key) {
    Object v = payload == null ? null : payload.get(key);
    return v == null ? "" : v.toString();
  }
}
