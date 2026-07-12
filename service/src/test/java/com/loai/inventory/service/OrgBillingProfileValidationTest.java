package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.OrgService.BillingProfile;
import org.junit.jupiter.api.Test;

/** Unit tests for the org billing-profile validation (length caps + email shape). */
class OrgBillingProfileValidationTest {

  private static BillingProfile of(String legalName, String email) {
    return new BillingProfile(legalName, null, null, null, null, null, null, email, null);
  }

  @Test
  void nullProfilePasses() {
    assertDoesNotThrow(() -> OrgService.validateBillingProfile(null));
  }

  @Test
  void allNullFieldsPass() {
    assertDoesNotThrow(() -> OrgService.validateBillingProfile(of(null, null)));
  }

  @Test
  void validProfilePasses() {
    assertDoesNotThrow(
        () -> OrgService.validateBillingProfile(of("Acme LLC", "shop@acme.example")));
  }

  @Test
  void overLongLegalNameRejected() {
    String tooLong = "x".repeat(256);
    assertThrows(
        ValidationException.class, () -> OrgService.validateBillingProfile(of(tooLong, null)));
  }

  @Test
  void malformedEmailRejected() {
    assertThrows(
        ValidationException.class,
        () -> OrgService.validateBillingProfile(of("Acme", "not-an-email")));
  }

  @Test
  void blankEmailPasses() {
    assertDoesNotThrow(() -> OrgService.validateBillingProfile(of("Acme", "   ")));
  }
}
