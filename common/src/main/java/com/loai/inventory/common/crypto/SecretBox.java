package com.loai.inventory.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption for secrets this system must store rather than merely verify — today,
 * per-merchant WhatsApp access tokens.
 *
 * <p><b>Why this exists at all.</b> Every other credential here is either hashed one-way (passwords
 * via bcrypt, magic-link and refresh tokens via SHA-256) or supplied by the operator at boot (JWT
 * secrets, SMTP password). A per-merchant WhatsApp token is neither: it must be replayed verbatim
 * to Meta on every send, and it belongs to the merchant, not the platform. Hashing is impossible
 * and plaintext-at-rest would mean one careless {@code SELECT *}, backup, or log line hands over
 * the ability to message that merchant's customers as them.
 *
 * <p><b>AES-256-GCM</b>: authenticated, so tampering fails loudly rather than decrypting to
 * garbage. A fresh 12-byte nonce per encryption is prepended to the ciphertext and Base64 is
 * applied to the whole thing, so the stored value is one self-contained string and no second column
 * is needed.
 *
 * <p><b>Deliberately not a key-management system.</b> The key arrives as one Base64 env var and
 * there is no rotation, no per-tenant key, no envelope encryption. That is the honest scope for a
 * first per-tenant secret: rotation needs a key id on each row and a re-encrypt job, which is real
 * work nobody has asked for yet, and pretending otherwise with an unused {@code key_version} column
 * would be worse than the plain statement here. What this DOES buy is that a database dump, a
 * replica, a backup or an accidental {@code SELECT *} is not a credential leak.
 *
 * <p><b>Fails closed.</b> No key configured means {@link #isConfigured()} is false and {@link
 * #encrypt} throws — the WhatsApp connect endpoint refuses rather than storing a token in the
 * clear.
 */
public final class SecretBox {

  private static final String ALGORITHM = "AES";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int NONCE_BYTES = 12; // GCM's standard nonce size
  private static final int TAG_BITS = 128;
  private static final int KEY_BYTES = 32; // AES-256

  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  private SecretBox(SecretKeySpec key) {
    this.key = key;
  }

  /**
   * Build from a Base64 key, or return a disabled box when {@code base64Key} is absent/blank.
   *
   * @throws IllegalArgumentException when a key is present but not exactly 32 bytes once decoded —
   *     a short key is a silent downgrade to a weaker cipher, so it is a startup failure, matching
   *     how {@code JWT_SECRET} is validated.
   */
  public static SecretBox fromBase64Key(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      return new SecretBox(null);
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64Key.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("secret key is not valid Base64", e);
    }
    if (raw.length != KEY_BYTES) {
      throw new IllegalArgumentException(
          "secret key must decode to exactly "
              + KEY_BYTES
              + " bytes (AES-256), got "
              + raw.length
              + " — generate one with: openssl rand -base64 32");
    }
    return new SecretBox(new SecretKeySpec(raw, ALGORITHM));
  }

  /** Whether a usable key was configured. Callers refuse to store secrets when this is false. */
  public boolean isConfigured() {
    return key != null;
  }

  /** {@code base64(nonce || ciphertext || tag)}. */
  public String encrypt(String plaintext) {
    requireKey();
    if (plaintext == null) {
      throw new IllegalArgumentException("nothing to encrypt");
    }
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      // Never include the plaintext in the message — this is the one exception path that has it.
      throw new IllegalStateException("failed to encrypt secret", e);
    }
  }

  /**
   * Reverse of {@link #encrypt}. Throws when the key is wrong or the value was tampered with (GCM
   * authenticates), never returning partial or garbage plaintext.
   */
  public String decrypt(String encoded) {
    requireKey();
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("nothing to decrypt");
    }
    try {
      byte[] all = Base64.getDecoder().decode(encoded);
      if (all.length <= NONCE_BYTES) {
        throw new IllegalArgumentException("ciphertext is too short to contain a nonce");
      }
      byte[] nonce = new byte[NONCE_BYTES];
      System.arraycopy(all, 0, nonce, 0, NONCE_BYTES);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("failed to decrypt secret (wrong key or tampered value)", e);
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException(
          "no encryption key configured — set WHATSAPP_TOKEN_KEY (openssl rand -base64 32)");
    }
  }
}
