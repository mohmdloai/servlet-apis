package com.loai.inventory.domain.model;

/** Lifecycle of a {@link Payment} (DB enum {@code payment_status}). */
public enum PaymentStatus {
  RECEIVED,
  PARTIALLY_ALLOCATED,
  ALLOCATED,
  PARTIALLY_REFUNDED,
  REFUNDED,
  DISPUTED
}
