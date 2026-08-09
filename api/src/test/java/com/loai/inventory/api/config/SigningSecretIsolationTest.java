package com.loai.inventory.api.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D7 — the customer and staff planes must never be signed with the same key. {@code
 * AppConfig.requireDistinctSigningSecrets} is the boot-time guard; these pin its three cases
 * (distinct, identical, and the same key under two Base64 spellings).
 */
class SigningSecretIsolationTest {

  private static final String STAFF =
      Base64.getEncoder().encodeToString("staff-plane-signing-key-0123456789".getBytes());
  private static final String CUSTOMER =
      Base64.getEncoder().encodeToString("customer-plane-signing-key-012345".getBytes());

  @Test
  @DisplayName("two different keys boot fine")
  void distinctSecretsPass() {
    assertDoesNotThrow(() -> AppConfig.requireDistinctSigningSecrets(STAFF, CUSTOMER));
  }

  @Test
  @DisplayName("the same key on both planes fails at boot")
  void identicalSecretsThrow() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> AppConfig.requireDistinctSigningSecrets(STAFF, STAFF));
    assertTrue(e.getMessage().contains("CUSTOMER_JWT_SECRET"), e.getMessage());
    assertTrue(e.getMessage().contains("JWT_SECRET"), e.getMessage());
  }

  @Test
  @DisplayName("the same key spelled two ways is still the same key")
  void sameBytesDifferentBase64Throw() {
    // Base64 without padding decodes to the same bytes — a string compare would have missed it.
    String unpadded = STAFF.replace("=", "");
    assertThrows(
        IllegalStateException.class,
        () -> AppConfig.requireDistinctSigningSecrets(STAFF, unpadded));
  }

  @Test
  @DisplayName("an undecodable value is left to JwtUtil's own message")
  void undecodableSecretIsNotThisGuardsProblem() {
    assertDoesNotThrow(() -> AppConfig.requireDistinctSigningSecrets(STAFF, "not base64 !!!"));
  }
}
