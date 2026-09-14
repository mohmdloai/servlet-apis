package com.loai.inventory.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Paymob's transaction-callback signature: HMAC-SHA512 over the concatenation — no separators — of
 * exactly {@link #SIGNED_FIELDS twenty fields of the transaction object, in that order}, delivered
 * lowercase-hex in the {@code ?hmac=} query parameter ({@code docs/paymob-card-epic.md} §HMAC).
 *
 * <p><b>The field list is pinned as a constant and must never be derived by sorting the payload's
 * keys.</b> The sorted order of these particular names happens to equal the canonical order, which
 * makes a sort-based implementation pass every test and then break silently the day Paymob adds a
 * field or renames one. A signature check that is accidentally correct is not a signature check.
 * ({@code PaymobSignatureTest} feeds a payload with extra unsigned keys precisely to catch that
 * implementation.)
 *
 * <p>This class knows nothing about JSON: the caller hands it a lookup from dotted field path to
 * the field's canonical string — booleans lowercase, numbers as printed, a JSON {@code null} or an
 * absent field as the empty string (never {@code "None"} or {@code "null"}). The JSON adapter lives
 * in the service module beside the parser.
 */
public final class PaymobSignature {

  /** The signed fields, in signing order. Pinned; see the class comment. */
  public static final List<String> SIGNED_FIELDS =
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
          "success");

  private static final String ALGORITHM = "HmacSHA512";
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private PaymobSignature() {}

  /** The string that is signed: every pinned field's canonical value, concatenated in order. */
  public static String canonical(Function<String, String> valueOf) {
    StringBuilder sb = new StringBuilder(256);
    for (String field : SIGNED_FIELDS) {
      String v = valueOf.apply(field);
      if (v != null) {
        sb.append(v);
      }
    }
    return sb.toString();
  }

  /** Lowercase-hex HMAC-SHA512 of {@code canonical} under {@code hmacSecret}. */
  public static String sign(String canonical, String hmacSecret) {
    if (hmacSecret == null || hmacSecret.isBlank()) {
      throw new IllegalArgumentException("hmac secret required");
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA512 unavailable", e);
    }
  }

  /**
   * Whether {@code presented} is the signature of the payload described by {@code valueOf}.
   * Constant-time on the comparison; a missing/blank signature is simply false.
   */
  public static boolean verify(
      Function<String, String> valueOf, String hmacSecret, String presented) {
    if (presented == null || presented.isBlank()) {
      return false;
    }
    byte[] expected = sign(canonical(valueOf), hmacSecret).getBytes(StandardCharsets.US_ASCII);
    byte[] given = presented.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, given);
  }

  private static String hex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }
}
