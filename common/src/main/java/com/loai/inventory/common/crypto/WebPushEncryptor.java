package com.loai.inventory.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Message encryption for Web Push — RFC 8291 over the {@code aes128gcm} content encoding of RFC
 * 8188 — on the JCE alone, pinned byte for byte to RFC 8291 Appendix A by {@code
 * WebPushEncryptorTest}.
 *
 * <p><b>Why in-house.</b> The alternative ({@code nl.martijndwars:web-push}) pulls BouncyCastle,
 * jose4j and Apache HttpAsyncClient into a codebase that has none of them. What Web Push actually
 * needs is small and fully covered by {@code java.security}/{@code javax.crypto}: an ephemeral
 * P-256 key pair, ECDH with the browser's {@code p256dh}, HKDF-SHA256 (a dozen lines over {@code
 * HmacSHA256}), AES-128-GCM, and the record framing. The two hard parts — the key-derivation info
 * strings and the uncompressed-point encoding — are exactly what the RFC's own test vector pins.
 *
 * <p><b>The scheme, in the RFC's terms</b> (sender = this server, receiver = the browser):
 *
 * <pre>
 *   ecdh_secret = ECDH(as_private, ua_public)
 *   key_info    = "WebPush: info" || 0x00 || ua_public || as_public
 *   PRK_key     = HMAC-SHA-256(auth_secret, ecdh_secret)
 *   IKM         = HMAC-SHA-256(PRK_key, key_info || 0x01)
 *   PRK         = HMAC-SHA-256(salt, IKM)
 *   CEK         = HMAC-SHA-256(PRK, "Content-Encoding: aes128gcm" || 0x00 || 0x01)[0..16]
 *   NONCE       = HMAC-SHA-256(PRK, "Content-Encoding: nonce"     || 0x00 || 0x01)[0..12]
 *   body        = salt(16) || rs(4) || idlen(1) || as_public(65) || AES-128-GCM(plaintext || 0x02)
 * </pre>
 *
 * <p>One record only: a push payload is capped at 4 KB by every push service, so the multi-record
 * framing of RFC 8188 is never needed and deliberately not implemented — a plaintext that would not
 * fit one record is refused up front.
 */
public final class WebPushEncryptor {

  /** RFC 8188 record size we declare; the whole message must fit in one record. */
  static final int RECORD_SIZE = 4096;

  /** Salt length per RFC 8188. */
  static final int SALT_BYTES = 16;

  private static final int AUTH_SECRET_BYTES = 16;
  private static final int CEK_BYTES = 16;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final byte PAD_DELIMITER_LAST = 0x02;

  /** The largest plaintext one record can carry: rs minus the GCM tag and the pad delimiter. */
  public static final int MAX_PLAINTEXT_BYTES = RECORD_SIZE - 16 - 1;

  private static final byte[] KEY_INFO_PREFIX = ascii("WebPush: info\u0000");
  private static final byte[] CEK_INFO = ascii("Content-Encoding: aes128gcm\u0000\u0001");
  private static final byte[] NONCE_INFO = ascii("Content-Encoding: nonce\u0000\u0001");

  private static final SecureRandom RANDOM = new SecureRandom();

  private WebPushEncryptor() {}

  /**
   * Encrypt {@code plaintext} for the subscription identified by its {@code p256dh} point and
   * {@code auth} secret, with a fresh ephemeral sender key and a random salt. The result is the
   * complete {@code aes128gcm} body to POST to the endpoint.
   *
   * @param uaPublicRaw the subscription's {@code p256dh}: a 65-byte uncompressed P-256 point
   * @param authSecret the subscription's {@code auth}: 16 bytes
   * @throws IllegalArgumentException on a malformed point/secret or an oversized plaintext
   */
  public static byte[] encrypt(byte[] plaintext, byte[] uaPublicRaw, byte[] authSecret) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    return encrypt(plaintext, uaPublicRaw, authSecret, P256.generate(), salt);
  }

  /**
   * The deterministic core: a caller-supplied sender key pair and salt. Public so the RFC 8291
   * Appendix A vector can be replayed exactly; production goes through {@link #encrypt(byte[],
   * byte[], byte[])}.
   */
  public static byte[] encrypt(
      byte[] plaintext, byte[] uaPublicRaw, byte[] authSecret, KeyPair sender, byte[] salt) {
    if (plaintext == null) {
      throw new IllegalArgumentException("nothing to encrypt");
    }
    if (plaintext.length > MAX_PLAINTEXT_BYTES) {
      throw new IllegalArgumentException(
          "push payload is " + plaintext.length + " bytes; the limit is " + MAX_PLAINTEXT_BYTES);
    }
    if (authSecret == null || authSecret.length != AUTH_SECRET_BYTES) {
      throw new IllegalArgumentException("auth secret must be exactly 16 bytes");
    }
    if (salt == null || salt.length != SALT_BYTES) {
      throw new IllegalArgumentException("salt must be exactly 16 bytes");
    }
    ECPublicKey uaPublic = P256.decodePoint(uaPublicRaw);
    byte[] asPublicRaw = P256.encodePoint((ECPublicKey) sender.getPublic());

    byte[] ecdhSecret = agree((ECPrivateKey) sender.getPrivate(), uaPublic);
    Keys keys = derive(ecdhSecret, authSecret, uaPublicRaw, asPublicRaw, salt);

    byte[] record = Arrays.copyOf(plaintext, plaintext.length + 1);
    record[plaintext.length] = PAD_DELIMITER_LAST;
    byte[] sealed = gcm(Cipher.ENCRYPT_MODE, keys, record);

    ByteBuffer out = ByteBuffer.allocate(SALT_BYTES + 4 + 1 + asPublicRaw.length + sealed.length);
    out.put(salt).putInt(RECORD_SIZE).put((byte) asPublicRaw.length).put(asPublicRaw).put(sealed);
    return out.array();
  }

  /**
   * The receiver side — what the browser does. Here for the round-trip test only; the server never
   * decrypts a push. Takes the message produced by {@link #encrypt} plus the receiver's key pair
   * and {@code auth} secret.
   */
  public static byte[] decrypt(byte[] body, KeyPair receiver, byte[] authSecret) {
    ByteBuffer in = ByteBuffer.wrap(body);
    byte[] salt = new byte[SALT_BYTES];
    in.get(salt);
    int rs = in.getInt();
    int idLen = in.get() & 0xff;
    byte[] asPublicRaw = new byte[idLen];
    in.get(asPublicRaw);
    byte[] sealed = new byte[in.remaining()];
    in.get(sealed);
    if (rs != RECORD_SIZE || idLen != P256.POINT_BYTES) {
      throw new IllegalArgumentException("not a single-record aes128gcm Web Push message");
    }
    ECPublicKey asPublic = P256.decodePoint(asPublicRaw);
    byte[] uaPublicRaw = P256.encodePoint((ECPublicKey) receiver.getPublic());
    byte[] ecdhSecret = agree((ECPrivateKey) receiver.getPrivate(), asPublic);
    Keys keys = derive(ecdhSecret, authSecret, uaPublicRaw, asPublicRaw, salt);
    byte[] record = gcm(Cipher.DECRYPT_MODE, keys, sealed);
    int end = record.length - 1;
    while (end >= 0 && record[end] == 0) {
      end--; // strip padding zeros
    }
    if (end < 0 || record[end] != PAD_DELIMITER_LAST) {
      throw new IllegalArgumentException("malformed record padding");
    }
    return Arrays.copyOf(record, end);
  }

  private record Keys(byte[] cek, byte[] nonce) {}

  private static Keys derive(
      byte[] ecdhSecret, byte[] authSecret, byte[] uaPublicRaw, byte[] asPublicRaw, byte[] salt) {
    byte[] keyInfo =
        ByteBuffer.allocate(KEY_INFO_PREFIX.length + uaPublicRaw.length + asPublicRaw.length + 1)
            .put(KEY_INFO_PREFIX)
            .put(uaPublicRaw)
            .put(asPublicRaw)
            .put((byte) 0x01)
            .array();
    byte[] prkKey = hmac(authSecret, ecdhSecret);
    byte[] ikm = hmac(prkKey, keyInfo);
    byte[] prk = hmac(salt, ikm);
    byte[] cek = Arrays.copyOf(hmac(prk, CEK_INFO), CEK_BYTES);
    byte[] nonce = Arrays.copyOf(hmac(prk, NONCE_INFO), NONCE_BYTES);
    return new Keys(cek, nonce);
  }

  private static byte[] agree(ECPrivateKey mine, ECPublicKey theirs) {
    try {
      KeyAgreement ka = KeyAgreement.getInstance("ECDH");
      ka.init(mine);
      ka.doPhase(theirs, true);
      return ka.generateSecret();
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("ECDH agreement failed", e);
    }
  }

  private static byte[] gcm(int mode, Keys keys, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          mode, new SecretKeySpec(keys.cek(), "AES"), new GCMParameterSpec(TAG_BITS, keys.nonce()));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-128-GCM failed", e);
    }
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 failed", e);
    }
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }
}
