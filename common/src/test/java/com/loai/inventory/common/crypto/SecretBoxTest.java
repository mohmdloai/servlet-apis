package com.loai.inventory.common.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String OTHER_KEY =
      Base64.getEncoder().encodeToString("another-32-byte-key-for-testing!".getBytes());

  private static final String TOKEN = "EAAG...a-real-looking-meta-access-token";

  @Test
  void roundTrips() {
    SecretBox box = SecretBox.fromBase64Key(KEY);
    assertEquals(TOKEN, box.decrypt(box.encrypt(TOKEN)));
  }

  @Test
  void theSamePlaintextEncryptsDifferentlyEveryTime() {
    SecretBox box = SecretBox.fromBase64Key(KEY);
    // A fresh nonce per call. Without it, equal ciphertexts would reveal that two merchants share
    // a token, or that a token was re-saved unchanged.
    assertNotEquals(box.encrypt(TOKEN), box.encrypt(TOKEN));
  }

  @Test
  void aTamperedValueFailsLoudlyRatherThanDecryptingToGarbage() {
    SecretBox box = SecretBox.fromBase64Key(KEY);
    String sealed = box.encrypt(TOKEN);
    byte[] raw = Base64.getDecoder().decode(sealed);
    raw[raw.length - 1] ^= 0x01; // flip one bit of the GCM tag
    String tampered = Base64.getEncoder().encodeToString(raw);

    assertThrows(IllegalStateException.class, () -> box.decrypt(tampered));
  }

  @Test
  void theWrongKeyCannotRead() {
    String sealed = SecretBox.fromBase64Key(KEY).encrypt(TOKEN);
    assertThrows(
        IllegalStateException.class, () -> SecretBox.fromBase64Key(OTHER_KEY).decrypt(sealed));
  }

  @Test
  void noKeyConfigured_failsClosed() {
    SecretBox box = SecretBox.fromBase64Key(null);
    assertFalse(box.isConfigured());
    // The connect endpoint checks isConfigured() and refuses; if it somehow did not, encrypting
    // must still not silently produce something storable.
    assertThrows(IllegalStateException.class, () -> box.encrypt(TOKEN));
    assertThrows(IllegalStateException.class, () -> box.decrypt("whatever"));
  }

  @Test
  void aShortKeyIsAStartupFailure_notASilentDowngrade() {
    String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> SecretBox.fromBase64Key(tooShort));
    assertTrue(e.getMessage().contains("32 bytes"), e.getMessage());
  }

  @Test
  void garbageCiphertextIsRejected() {
    SecretBox box = SecretBox.fromBase64Key(KEY);
    assertThrows(RuntimeException.class, () -> box.decrypt("not-base64-at-all!!"));
    assertThrows(
        RuntimeException.class, () -> box.decrypt(Base64.getEncoder().encodeToString(new byte[4])));
  }
}
