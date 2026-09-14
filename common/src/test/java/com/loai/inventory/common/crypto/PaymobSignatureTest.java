package com.loai.inventory.common.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pinned twenty-field HMAC ({@code docs/paymob-card-epic.md} §HMAC). The oracle signatures
 * below were computed with an independent implementation (Python's {@code hmac} + {@code
 * hashlib.sha512}) over the same canonical strings, so a Java bug cannot agree with itself.
 */
class PaymobSignatureTest {

  private static final String SECRET = "k";

  /** Every signed field, a value each, in a deliberately scrambled insertion order. */
  private static Map<String, String> sample() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("success", "true");
    m.put("source_data.type", "card");
    m.put("amount_cents", "100");
    m.put("owner", "4");
    m.put("created_at", "2026-01-01T00:00:00");
    m.put("is_voided", "false");
    m.put("currency", "EGP");
    m.put("error_occured", "false");
    m.put("has_parent_transaction", "false");
    m.put("id", "1");
    m.put("integration_id", "2");
    m.put("is_3d_secure", "true");
    m.put("is_auth", "false");
    m.put("is_capture", "false");
    m.put("is_refunded", "false");
    m.put("is_standalone_payment", "true");
    m.put("order.id", "3");
    m.put("pending", "false");
    m.put("source_data.pan", "");
    m.put("source_data.sub_type", "Visa");
    return m;
  }

  private static final String SAMPLE_CANONICAL =
      "1002026-01-01T00:00:00EGPfalsefalse12truefalsefalsefalsetruefalse34falseVisacardtrue";

  private static final String SAMPLE_SIGNATURE =
      "e8fae84f7b565c9531d3dd6226cdd10adc9329c417cab4e4f07ecdaa5a991b355d9b22893380ec4fa298d8228548"
          + "ec6a83e40c28072d2c3988577c63503fb858";

  @Test
  void signedFields_areExactlyTheTwenty_inPaymobsOrder() {
    // Pinned, not derived. An edit here is an edit to the wire contract and must be deliberate.
    assertEquals(
        List.of(
            "amount_cents",
            "created_at",
            "currency",
            "error_occured",
            "has_parent_transaction",
            "id",
            "integration_id",
            "is_3d_secure",
            "is_auth",
            "is_capture",
            "is_refunded",
            "is_standalone_payment",
            "is_voided",
            "order.id",
            "owner",
            "pending",
            "source_data.pan",
            "source_data.sub_type",
            "source_data.type",
            "success"),
        PaymobSignature.SIGNED_FIELDS);
  }

  @Test
  void canonical_concatenatesInPinnedOrder_regardlessOfPayloadOrder() {
    Map<String, String> m = sample();
    assertEquals(SAMPLE_CANONICAL, PaymobSignature.canonical(m::get));
  }

  @Test
  void sign_matchesTheIndependentOracle() {
    assertEquals(SAMPLE_SIGNATURE, PaymobSignature.sign(SAMPLE_CANONICAL, SECRET));
  }

  @Test
  void verify_acceptsTheOracle_lowercaseAndUppercaseHexAlike() {
    Map<String, String> m = sample();
    assertTrue(PaymobSignature.verify(m::get, SECRET, SAMPLE_SIGNATURE));
    assertTrue(PaymobSignature.verify(m::get, SECRET, SAMPLE_SIGNATURE.toUpperCase()));
    assertTrue(PaymobSignature.verify(m::get, SECRET, "  " + SAMPLE_SIGNATURE + "\n"));
  }

  @Test
  void verify_rejectsTamperedAmount_wrongKey_absentSignature() {
    Map<String, String> tampered = sample();
    tampered.put("amount_cents", "1");
    assertFalse(PaymobSignature.verify(tampered::get, SECRET, SAMPLE_SIGNATURE));

    Map<String, String> m = sample();
    assertFalse(PaymobSignature.verify(m::get, "not-the-key", SAMPLE_SIGNATURE));
    assertFalse(PaymobSignature.verify(m::get, SECRET, null));
    assertFalse(PaymobSignature.verify(m::get, SECRET, ""));
    assertFalse(PaymobSignature.verify(m::get, SECRET, SAMPLE_SIGNATURE.substring(1)));
  }

  @Test
  void nullOrAbsentField_contributesEmptyString_notNoneOrNull() {
    Map<String, String> m = sample();
    m.remove("source_data.pan"); // absent → lookup returns null
    assertEquals(SAMPLE_CANONICAL, PaymobSignature.canonical(m::get));
    assertTrue(PaymobSignature.verify(m::get, SECRET, SAMPLE_SIGNATURE));

    Map<String, String> wrong = sample();
    wrong.put("source_data.pan", "None"); // the Python-ism a naive port produces
    assertFalse(PaymobSignature.verify(wrong::get, SECRET, SAMPLE_SIGNATURE));
  }

  /**
   * The test that catches a sort-based implementation. The sorted order of the twenty signed names
   * happens to equal the canonical order — so a signer that "sorts the payload's keys" passes every
   * other test here. Real callbacks carry many UNSIGNED keys (is_void, is_refund, profile_id, data,
   * order.merchant_order_id, …) which such a signer would sweep in. The pinned list must ignore
   * them.
   */
  @Test
  void extraUnsignedFieldsInThePayload_doNotChangeTheSignature() {
    Map<String, String> withExtras = sample();
    withExtras.put("is_void", "false");
    withExtras.put("is_refund", "false");
    withExtras.put("profile_id", "1122");
    withExtras.put("api_source", "IFRAME");
    withExtras.put("order.merchant_order_id", "3fa85f64-5717-4562-b3fc-2c963f66afa6");
    withExtras.put("merchant_commission", "0");
    withExtras.put("data.message", "Approved");
    assertEquals(SAMPLE_CANONICAL, PaymobSignature.canonical(withExtras::get));
    assertTrue(PaymobSignature.verify(withExtras::get, SECRET, SAMPLE_SIGNATURE));

    // And the shape of the mistake, spelled out: signing over sorted payload keys differs.
    List<String> sortedKeys = new ArrayList<>(withExtras.keySet());
    Collections.sort(sortedKeys);
    StringBuilder sortedCanonical = new StringBuilder();
    for (String k : sortedKeys) {
      sortedCanonical.append(withExtras.get(k));
    }
    assertNotEquals(SAMPLE_CANONICAL, sortedCanonical.toString());
    assertNotEquals(SAMPLE_SIGNATURE, PaymobSignature.sign(sortedCanonical.toString(), SECRET));
  }

  @Test
  void swappingTwoPinnedFields_changesTheSignature() {
    // Order is part of the contract: same twenty values, one adjacent swap, different string.
    Map<String, String> m = sample();
    String swapped = SAMPLE_CANONICAL.replace("Visacard", "cardVisa"); // sub_type/type transposed
    assertNotEquals(SAMPLE_CANONICAL, swapped);
    assertNotEquals(
        PaymobSignature.sign(SAMPLE_CANONICAL, SECRET), PaymobSignature.sign(swapped, SECRET));
    assertTrue(PaymobSignature.verify(m::get, SECRET, SAMPLE_SIGNATURE));
  }
}
