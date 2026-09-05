package com.loai.inventory.common.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * RFC 8291 Appendix A, byte for byte. Given the RFC's fixed receiver key pair, sender key pair,
 * authentication secret and salt, the encryptor must produce exactly the RFC's message — that is
 * the guarantee a library would have offered, without the library.
 */
class WebPushEncryptorTest {

  // RFC 8291 Appendix A
  private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
  private static final String AS_PUBLIC =
      "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
  private static final String UA_PUBLIC =
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
  private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
  private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
  private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
  private static final String MESSAGE =
      "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
          + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
          + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

  @Test
  void rfc8291AppendixA_producesTheExactCiphertext() {
    KeyPair sender =
        new KeyPair(P256.decodePoint(b64(AS_PUBLIC)), P256.decodeScalar(b64(AS_PRIVATE)));

    byte[] body =
        WebPushEncryptor.encrypt(
            PLAINTEXT.getBytes(StandardCharsets.UTF_8),
            b64(UA_PUBLIC),
            b64(AUTH_SECRET),
            sender,
            b64(SALT));

    assertEquals(144, body.length, "header 86 + record 41 + delimiter 1 + tag 16");
    assertArrayEquals(b64(MESSAGE), body);
  }

  @Test
  void theRfcMessage_decryptsWithTheReceiverKey() {
    KeyPair receiver =
        new KeyPair(P256.decodePoint(b64(UA_PUBLIC)), P256.decodeScalar(b64(UA_PRIVATE)));

    byte[] plain = WebPushEncryptor.decrypt(b64(MESSAGE), receiver, b64(AUTH_SECRET));

    assertEquals(PLAINTEXT, new String(plain, StandardCharsets.UTF_8));
  }

  @Test
  void aRandomPayload_roundTripsUnderAFreshEphemeralKey() {
    KeyPair receiver = P256.generate();
    byte[] auth = new byte[16];
    new SecureRandom().nextBytes(auth);
    byte[] payload = new byte[1500];
    new SecureRandom().nextBytes(payload);
    byte[] uaPublic = P256.encodePoint((java.security.interfaces.ECPublicKey) receiver.getPublic());

    byte[] first = WebPushEncryptor.encrypt(payload, uaPublic, auth);
    byte[] second = WebPushEncryptor.encrypt(payload, uaPublic, auth);

    assertArrayEquals(payload, WebPushEncryptor.decrypt(first, receiver, auth));
    assertArrayEquals(payload, WebPushEncryptor.decrypt(second, receiver, auth));
    // A fresh key + salt every time: the same plaintext never produces the same message.
    assertEquals(false, java.util.Arrays.equals(first, second));
  }

  @Test
  void aP256dhThatIsNotOnTheCurve_isRejectedBeforeAnyAgreement() {
    byte[] offCurve = b64(UA_PUBLIC);
    offCurve[10] ^= 0x01; // flip one bit of X — no longer a point on P-256

    assertThrows(
        IllegalArgumentException.class,
        () -> WebPushEncryptor.encrypt(new byte[] {1}, offCurve, b64(AUTH_SECRET)));
  }

  @Test
  void malformedInputs_areRefusedUpFront() {
    byte[] uaPublic = b64(UA_PUBLIC);
    assertThrows(
        IllegalArgumentException.class,
        () -> WebPushEncryptor.encrypt(new byte[] {1}, new byte[64], b64(AUTH_SECRET)));
    assertThrows(
        IllegalArgumentException.class,
        () -> WebPushEncryptor.encrypt(new byte[] {1}, uaPublic, new byte[15]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WebPushEncryptor.encrypt(
                new byte[WebPushEncryptor.MAX_PLAINTEXT_BYTES + 1], uaPublic, b64(AUTH_SECRET)));
  }

  @Test
  void scalarAndPointEncodings_roundTrip() {
    KeyPair pair = P256.generate();
    byte[] point = P256.encodePoint((java.security.interfaces.ECPublicKey) pair.getPublic());
    byte[] scalar = P256.encodeScalar((java.security.interfaces.ECPrivateKey) pair.getPrivate());

    assertEquals(65, point.length);
    assertEquals(32, scalar.length);
    assertArrayEquals(point, P256.encodePoint(P256.decodePoint(point)));
    assertArrayEquals(scalar, P256.encodeScalar(P256.decodeScalar(scalar)));
  }

  private static byte[] b64(String s) {
    return Base64.getUrlDecoder().decode(s);
  }
}
