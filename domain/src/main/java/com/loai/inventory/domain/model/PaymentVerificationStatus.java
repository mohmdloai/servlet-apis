package com.loai.inventory.domain.model;

/**
 * Verification state of a {@link PaymentTransaction} (DB enum {@code payment_verification_status}).
 */
public enum PaymentVerificationStatus {
  UNVERIFIED,
  VERIFIED,
  NOT_FOUND,
  ABANDONED
}
