package com.loai.inventory.domain.model;

/**
 * Lifecycle of a {@link Refund}: PENDING → EXECUTED, with a CANCELLED branch off PENDING. Mirrors
 * the {@code refund_status} DB enum (V26). The DEBIT {@link PaymentTransaction} is created at the
 * PENDING → EXECUTED transition.
 */
public enum RefundStatus {
  PENDING,
  EXECUTED,
  CANCELLED
}
