package com.loai.inventory.common.crypto;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

/**
 * The P-256 ({@code secp256r1}) key plumbing Web Push needs and the JCE does not hand out directly:
 * the 65-byte <em>uncompressed point</em> encoding ({@code 0x04 || X || Y}) that RFC 8291 puts on
 * the wire for both parties' public keys, and the raw 32-byte scalar for a private key.
 *
 * <p>No BouncyCastle: everything here is {@code java.security} plus a dozen lines of {@link
 * BigInteger} to check that a point a browser handed us actually lies on the curve — the JCE will
 * happily build an {@link ECPublicKey} from off-curve coordinates, and ECDH against one is the
 * classic invalid-curve attack, so a subscription's {@code p256dh} is validated here before it is
 * ever agreed with.
 */
public final class P256 {

  /** The uncompressed-point length: one tag byte plus two 32-byte coordinates. */
  public static final int POINT_BYTES = 65;

  /** The private scalar length. */
  public static final int SCALAR_BYTES = 32;

  private static final ECParameterSpec PARAMS = loadParams();

  private P256() {}

  private static ECParameterSpec loadParams() {
    try {
      AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
      p.init(new ECGenParameterSpec("secp256r1"));
      return p.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("secp256r1 is not available in this JDK", e);
    }
  }

  /** The curve parameters (shared by everything that builds a key here). */
  public static ECParameterSpec params() {
    return PARAMS;
  }

  /** A fresh key pair — the ephemeral sender key for one encryption, or a new VAPID identity. */
  public static KeyPair generate() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot generate a P-256 key pair", e);
    }
  }

  /** {@code 0x04 || X || Y}, each coordinate left-padded to 32 bytes. */
  public static byte[] encodePoint(ECPublicKey key) {
    ECPoint w = key.getW();
    byte[] out = new byte[POINT_BYTES];
    out[0] = 0x04;
    System.arraycopy(fixed(w.getAffineX()), 0, out, 1, SCALAR_BYTES);
    System.arraycopy(fixed(w.getAffineY()), 0, out, 1 + SCALAR_BYTES, SCALAR_BYTES);
    return out;
  }

  /**
   * Parse an uncompressed point into a public key, <b>rejecting</b> anything that is not exactly 65
   * bytes, not tagged {@code 0x04}, or not on the curve.
   *
   * @throws IllegalArgumentException when the bytes are not a valid P-256 point
   */
  public static ECPublicKey decodePoint(byte[] raw) {
    if (raw == null || raw.length != POINT_BYTES || raw[0] != 0x04) {
      throw new IllegalArgumentException(
          "not an uncompressed P-256 point (expected 65 bytes starting with 0x04)");
    }
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + SCALAR_BYTES));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 1 + SCALAR_BYTES, POINT_BYTES));
    requireOnCurve(x, y);
    try {
      return (ECPublicKey)
          KeyFactory.getInstance("EC")
              .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), PARAMS));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not a valid P-256 public key", e);
    }
  }

  /** The private scalar, left-padded to 32 bytes. */
  public static byte[] encodeScalar(ECPrivateKey key) {
    return fixed(key.getS());
  }

  /**
   * Parse a raw 32-byte scalar into a private key.
   *
   * @throws IllegalArgumentException when the bytes are not 32 long or the scalar is out of range
   */
  public static ECPrivateKey decodeScalar(byte[] raw) {
    if (raw == null || raw.length != SCALAR_BYTES) {
      throw new IllegalArgumentException("not a P-256 private scalar (expected 32 bytes)");
    }
    BigInteger s = new BigInteger(1, raw);
    if (s.signum() <= 0 || s.compareTo(PARAMS.getOrder()) >= 0) {
      throw new IllegalArgumentException("P-256 private scalar is out of range");
    }
    try {
      return (ECPrivateKey)
          KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(s, PARAMS));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not a valid P-256 private key", e);
    }
  }

  /** {@code y² ≡ x³ + ax + b (mod p)}, with both coordinates inside the field. */
  private static void requireOnCurve(BigInteger x, BigInteger y) {
    BigInteger p = ((ECFieldFp) PARAMS.getCurve().getField()).getP();
    if (x.signum() < 0 || x.compareTo(p) >= 0 || y.signum() < 0 || y.compareTo(p) >= 0) {
      throw new IllegalArgumentException("P-256 point coordinates are outside the field");
    }
    BigInteger a = PARAMS.getCurve().getA();
    BigInteger b = PARAMS.getCurve().getB();
    BigInteger lhs = y.multiply(y).mod(p);
    BigInteger rhs = x.pow(3).add(a.multiply(x)).add(b).mod(p);
    if (!lhs.equals(rhs)) {
      throw new IllegalArgumentException("P-256 point is not on the curve");
    }
  }

  private static byte[] fixed(BigInteger v) {
    byte[] raw = v.toByteArray();
    if (raw.length == SCALAR_BYTES) {
      return raw;
    }
    byte[] out = new byte[SCALAR_BYTES];
    if (raw.length > SCALAR_BYTES) {
      // A leading sign byte from toByteArray(); the magnitude itself always fits.
      System.arraycopy(raw, raw.length - SCALAR_BYTES, out, 0, SCALAR_BYTES);
    } else {
      System.arraycopy(raw, 0, out, SCALAR_BYTES - raw.length, raw.length);
    }
    return out;
  }
}
