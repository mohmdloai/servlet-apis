package com.loai.inventory.service.paymob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.crypto.PaymobSignature;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The JSON side of the signature: a realistic Paymob "transaction processed" body — nested {@code
 * order}/{@code source_data}, a {@code null} pan, many unsigned fields — and the HMAC an
 * independent implementation (Python {@code hmac}/{@code hashlib}) computed over it with the test
 * secret. Passing this test with the pinned list, and failing the sorted-keys variant, is the whole
 * point of pinning.
 */
class PaymobCallbackTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SECRET = "test-hmac-secret-0123456789ABCDEF";

  static final String KNOWN_GOOD_BODY =
      "{\"type\":\"TRANSACTION\",\"obj\":{\"id\":987654321,\"pending\":false,\"amount_cents\":25000,"
          + "\"success\":true,\"is_auth\":false,\"is_capture\":false,\"is_standalone_payment\":true,"
          + "\"is_voided\":false,\"is_refunded\":false,\"is_3d_secure\":true,\"integration_id\":4938201,"
          + "\"profile_id\":1122,\"has_parent_transaction\":false,\"order\":{\"id\":555000111,"
          + "\"created_at\":\"2026-09-14T08:00:00.000000\","
          + "\"merchant_order_id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\",\"amount_cents\":25000,"
          + "\"currency\":\"EGP\",\"paid_amount_cents\":25000},"
          + "\"created_at\":\"2026-09-14T08:01:02.123456\",\"currency\":\"EGP\","
          + "\"source_data\":{\"pan\":null,\"type\":\"card\",\"sub_type\":\"MasterCard\"},"
          + "\"api_source\":\"IFRAME\",\"error_occured\":false,\"owner\":3344,"
          + "\"parent_transaction\":null,\"is_void\":false,\"is_refund\":false,"
          + "\"data\":{\"message\":\"Approved\",\"txn_response_code\":\"APPROVED\"},"
          + "\"payment_key_claims\":{\"extra\":{\"org_id\":\"o\",\"sales_order_id\":\"s\","
          + "\"intent_id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"},\"currency\":\"EGP\"},"
          + "\"merchant_commission\":0}}";

  static final String KNOWN_GOOD_CANONICAL =
      "250002026-09-14T08:01:02.123456EGPfalsefalse9876543214938201truefalsefalsefalsetruefalse"
          + "5550001113344falseMasterCardcardtrue";

  static final String KNOWN_GOOD_HMAC =
      "2afd2bb5d63a612bd870445704c1a41ab8ecdd0680d9dfea2d746d08d2f91ccd6a8c478766dc1b59345c3361c358"
          + "315d930111c2177d138a955bec83c5f983a8";

  @Test
  void knownGoodSample_canonicalString_andSignature() {
    PaymobCallback cb = PaymobCallback.parse(MAPPER, KNOWN_GOOD_BODY);

    assertEquals(KNOWN_GOOD_CANONICAL, PaymobSignature.canonical(cb.signedValues()));
    assertTrue(PaymobSignature.verify(cb.signedValues(), SECRET, KNOWN_GOOD_HMAC));
  }

  @Test
  void stringification_booleansLowercase_numbersAsDigits_nullPanEmpty() {
    PaymobCallback cb = PaymobCallback.parse(MAPPER, KNOWN_GOOD_BODY);

    assertEquals("true", cb.signedValue("success"));
    assertEquals("false", cb.signedValue("pending"));
    assertEquals("25000", cb.signedValue("amount_cents"));
    assertEquals("555000111", cb.signedValue("order.id"));
    assertEquals("", cb.signedValue("source_data.pan"), "JSON null → empty, never \"null\"");
    assertEquals("", cb.signedValue("no.such.field"), "absent → empty");
  }

  @Test
  void tamperedAmount_failsTheKnownGoodSignature() {
    String tampered =
        KNOWN_GOOD_BODY.replace(
            "\"amount_cents\":25000,\"success\"", "\"amount_cents\":1,\"success\"");
    PaymobCallback cb = PaymobCallback.parse(MAPPER, tampered);
    assertEquals("1", cb.signedValue("amount_cents"));
    assertFalse(PaymobSignature.verify(cb.signedValues(), SECRET, KNOWN_GOOD_HMAC));
  }

  @Test
  void typedAccessors_readTheSample() {
    PaymobCallback cb = PaymobCallback.parse(MAPPER, KNOWN_GOOD_BODY);

    assertTrue(cb.isTransaction());
    assertEquals("987654321", cb.transactionId());
    assertEquals(25000L, cb.amountCents());
    assertEquals("EGP", cb.currency());
    assertTrue(cb.success());
    assertFalse(cb.pending());
    assertTrue(cb.isSettled());
    assertEquals("555000111", cb.paymobOrderId());
    assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", cb.merchantOrderId());
    assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", cb.extraIntentId());
    assertEquals(
        OffsetDateTime.of(2026, 9, 14, 8, 1, 2, 123_456_000, ZoneOffset.UTC), cb.createdAt());
  }

  @Test
  void tokenCallback_isNotATransaction() {
    PaymobCallback cb =
        PaymobCallback.parse(MAPPER, "{\"type\":\"TOKEN\",\"obj\":{\"id\":1,\"token\":\"x\"}}");
    assertFalse(cb.isTransaction());
    assertEquals("TOKEN", cb.type());
  }

  @Test
  void parse_rejectsWhatCannotBeVerified() {
    assertThrows(IllegalArgumentException.class, () -> PaymobCallback.parse(MAPPER, null));
    assertThrows(IllegalArgumentException.class, () -> PaymobCallback.parse(MAPPER, "  "));
    assertThrows(IllegalArgumentException.class, () -> PaymobCallback.parse(MAPPER, "not json"));
    assertThrows(IllegalArgumentException.class, () -> PaymobCallback.parse(MAPPER, "[1,2]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> PaymobCallback.parse(MAPPER, "{\"type\":\"TRANSACTION\"}"),
        "no obj → nothing to sign over");
  }

  @Test
  void missingFields_readAsAbsent_notAsCrashes() {
    PaymobCallback cb = PaymobCallback.parse(MAPPER, "{\"type\":\"TRANSACTION\",\"obj\":{}}");
    assertNull(cb.transactionId());
    assertEquals(-1L, cb.amountCents());
    assertNull(cb.currency());
    assertFalse(cb.success());
    assertNull(cb.paymobOrderId());
    assertNull(cb.merchantOrderId());
    assertNull(cb.createdAt());
  }
}
