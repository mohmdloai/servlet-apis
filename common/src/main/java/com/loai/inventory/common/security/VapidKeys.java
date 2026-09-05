package com.loai.inventory.common.security;

import com.loai.inventory.common.crypto.P256;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * The application server's VAPID identity (RFC 8292): one P-256 key pair, configured as two
 * base64url env values — the raw 65-byte uncompressed public point and the raw 32-byte private
 * scalar, exactly the format {@code npx web-push generate-vapid-keys} prints and the browser's
 * {@code applicationServerKey} consumes.
 *
 * <p><b>The SecretBox rule:</b> both unset → {@link #isConfigured()} false and the app boots with
 * push disabled; a value present but malformed — wrong length, off-curve, or a public key that does
 * not match the private one — is a startup failure, never a silently dead channel.
 */
public final class VapidKeys {

  private final ECPublicKey publicKey;
  private final ECPrivateKey privateKey;
  private final String publicKeyBase64Url;

  private VapidKeys(ECPublicKey publicKey, ECPrivateKey privateKey, String publicKeyBase64Url) {
    this.publicKey = publicKey;
    this.privateKey = privateKey;
    this.publicKeyBase64Url = publicKeyBase64Url;
  }

  /** A disabled instance: no key pair, push off. */
  public static VapidKeys disabled() {
    return new VapidKeys(null, null, null);
  }

  /**
   * Parse the two env values. Either blank → {@link #disabled()}.
   *
   * @throws IllegalArgumentException when only one is set, or either does not decode to a valid
   *     P-256 key, or the two do not belong together
   */
  public static VapidKeys fromBase64Url(String publicKeyB64, String privateKeyB64) {
    boolean hasPublic = publicKeyB64 != null && !publicKeyB64.isBlank();
    boolean hasPrivate = privateKeyB64 != null && !privateKeyB64.isBlank();
    if (!hasPublic && !hasPrivate) {
      return disabled();
    }
    if (hasPublic != hasPrivate) {
      throw new IllegalArgumentException(
          "WEB_PUSH_VAPID_PUBLIC_KEY and WEB_PUSH_VAPID_PRIVATE_KEY must be set together");
    }
    byte[] pub;
    byte[] priv;
    try {
      pub = Base64.getUrlDecoder().decode(publicKeyB64.strip());
      priv = Base64.getUrlDecoder().decode(privateKeyB64.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("VAPID keys must be base64url", e);
    }
    ECPublicKey publicKey;
    ECPrivateKey privateKey;
    try {
      publicKey = P256.decodePoint(pub);
      privateKey = P256.decodeScalar(priv);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "VAPID keys are malformed (public = 65-byte uncompressed P-256 point, private = 32-byte"
              + " scalar, both base64url — generate with: npx web-push generate-vapid-keys): "
              + e.getMessage(),
          e);
    }
    requireMatching(publicKey, privateKey);
    return new VapidKeys(
        publicKey, privateKey, Base64.getUrlEncoder().withoutPadding().encodeToString(pub));
  }

  /** From an in-memory key pair (tests, key generation). */
  public static VapidKeys of(KeyPair pair) {
    ECPublicKey pub = (ECPublicKey) pair.getPublic();
    return new VapidKeys(
        pub,
        (ECPrivateKey) pair.getPrivate(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(P256.encodePoint(pub)));
  }

  public boolean isConfigured() {
    return privateKey != null;
  }

  public ECPublicKey publicKey() {
    requireConfigured();
    return publicKey;
  }

  public ECPrivateKey privateKey() {
    requireConfigured();
    return privateKey;
  }

  /** The value the browser needs as {@code applicationServerKey} and the VAPID {@code k=}. */
  public String publicKeyBase64Url() {
    requireConfigured();
    return publicKeyBase64Url;
  }

  /**
   * A mismatched pair would sign JWTs no push service accepts — and it would only show up as a 401
   * on every send, four retries deep. Cheaper to sign and verify once at startup.
   */
  private static void requireMatching(ECPublicKey publicKey, ECPrivateKey privateKey) {
    try {
      byte[] probe = "vapid".getBytes(StandardCharsets.US_ASCII);
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(privateKey);
      signer.update(probe);
      byte[] sig = signer.sign();
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(publicKey);
      verifier.update(probe);
      if (!verifier.verify(sig)) {
        throw new IllegalArgumentException(
            "WEB_PUSH_VAPID_PUBLIC_KEY does not belong to WEB_PUSH_VAPID_PRIVATE_KEY");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("VAPID key pair cannot sign", e);
    }
  }

  private void requireConfigured() {
    if (privateKey == null) {
      throw new IllegalStateException("no VAPID key pair configured");
    }
  }
}
