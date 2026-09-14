package com.loai.inventory.service.paymob;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.crypto.PaymobSignature;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/**
 * A parsed Paymob "transaction processed" callback: {@code {"type":"TRANSACTION","obj":{…}}}.
 * Read-only view over the JSON tree plus the one thing {@link PaymobSignature} needs from JSON —
 * the canonical string of a signed field ({@link #signedValue}): booleans lowercase, numbers as
 * printed, a JSON {@code null} or an absent field as {@code ""}.
 */
public final class PaymobCallback {

  public static final String TYPE_TRANSACTION = "TRANSACTION";

  private final JsonNode root;
  private final JsonNode obj;

  private PaymobCallback(JsonNode root) {
    this.root = root;
    this.obj = root.path("obj");
  }

  /**
   * @throws IllegalArgumentException when the body is not a JSON object carrying an {@code obj}
   *     object — nothing about such a body can be verified
   */
  public static PaymobCallback parse(ObjectMapper mapper, String body) {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("empty body");
    }
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("malformed JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("body is not a JSON object");
    }
    if (!root.path("obj").isObject()) {
      throw new IllegalArgumentException("body carries no obj object");
    }
    return new PaymobCallback(root);
  }

  /** {@code type} — {@code TRANSACTION} for a payment; {@code TOKEN} for a saved-card callback. */
  public String type() {
    return text(root.path("type"));
  }

  public boolean isTransaction() {
    return TYPE_TRANSACTION.equals(type());
  }

  /** The lookup {@link PaymobSignature} signs over — a dotted path into {@code obj}. */
  public Function<String, String> signedValues() {
    return this::signedValue;
  }

  /** The canonical string of one signed field of {@code obj}; {@code ""} for null/absent. */
  public String signedValue(String dottedPath) {
    JsonNode node = obj;
    for (String segment : dottedPath.split("\\.")) {
      node = node.path(segment);
    }
    if (node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isBoolean()) {
      return node.booleanValue() ? "true" : "false";
    }
    // Numbers as printed (amount_cents, id, order.id, integration_id, owner are integers), strings
    // verbatim. Containers never appear in the signed list.
    return node.asText();
  }

  // The transaction's own facts. Every accessor is null-safe: a missing field reads as null/false.

  /** {@code obj.id} — Paymob's transaction id, our {@code provider_ref}. */
  public String transactionId() {
    JsonNode id = obj.path("id");
    return id.isMissingNode() || id.isNull() ? null : id.asText();
  }

  /** {@code obj.amount_cents} — integer piastres, or -1 when absent/unparseable. */
  public long amountCents() {
    JsonNode n = obj.path("amount_cents");
    if (n.isNumber()) {
      return n.longValue();
    }
    if (n.isTextual()) {
      try {
        return Long.parseLong(n.asText().strip());
      } catch (NumberFormatException e) {
        return -1;
      }
    }
    return -1;
  }

  public String currency() {
    return text(obj.path("currency"));
  }

  public boolean success() {
    return obj.path("success").asBoolean(false);
  }

  public boolean pending() {
    return obj.path("pending").asBoolean(false);
  }

  public boolean errorOccured() {
    // Paymob's spelling ("occured") is part of the wire contract and the signed field list.
    return obj.path("error_occured").asBoolean(false);
  }

  public boolean isAuth() {
    return obj.path("is_auth").asBoolean(false);
  }

  public boolean isCapture() {
    return obj.path("is_capture").asBoolean(false);
  }

  public boolean isVoided() {
    return obj.path("is_voided").asBoolean(false);
  }

  public boolean isRefunded() {
    return obj.path("is_refunded").asBoolean(false);
  }

  public boolean hasParentTransaction() {
    return obj.path("has_parent_transaction").asBoolean(false);
  }

  /** {@code obj.order.id} — the Paymob-side order; in the signed field list. */
  public String paymobOrderId() {
    JsonNode id = obj.path("order").path("id");
    return id.isMissingNode() || id.isNull() ? null : id.asText();
  }

  /**
   * {@code obj.order.merchant_order_id} — Paymob's echo of the intention's {@code
   * special_reference}, i.e. our intent id. <b>Not</b> in the signed field list, which is why the
   * webhook also binds the callback to its intent through the signed {@link #paymobOrderId()}.
   */
  public String merchantOrderId() {
    return text(obj.path("order").path("merchant_order_id"));
  }

  /** {@code obj.payment_key_claims.extra.intent_id} — our {@code extras} echoed back, if any. */
  public String extraIntentId() {
    return text(obj.path("payment_key_claims").path("extra").path("intent_id"));
  }

  /**
   * {@code obj.created_at} as an instant, or null. Paymob prints a naive local timestamp ({@code
   * 2026-09-14T14:27:50.358441}) in the merchant region's zone ({@link PaymobHosts#zoneForRegion})
   * — an offset, when one is ever present, wins. Informational: the ledger's {@code occurred_at};
   * the recording clock is the server's own.
   */
  public OffsetDateTime createdAt(ZoneId naiveZone) {
    String raw = text(obj.path("created_at"));
    if (raw == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(raw);
    } catch (DateTimeParseException ignored) {
      // fall through — no zone on the wire
    }
    try {
      return LocalDateTime.parse(raw).atZone(naiveZone).toOffsetDateTime();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Money moved and stayed moved: a successful, captured, non-reversed transaction. The epic's
   * classification, in one place.
   */
  public boolean isSettled() {
    return success() && !errorOccured() && !isVoided() && !isRefunded();
  }

  private static String text(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return null;
    }
    String s = n.asText();
    return s == null || s.isBlank() ? null : s;
  }
}
