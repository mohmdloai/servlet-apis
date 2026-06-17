package com.loai.inventory.domain.model;

/**
 * Reconciliation outcome of a VERIFIED {@link PaymentTransaction} against a sales order (DB enum
 * {@code payment_reconciliation_status}). Only meaningful once {@code
 * verification_status=VERIFIED}.
 */
public enum PaymentReconciliationStatus {
  PENDING,
  MATCHED,
  ORPHAN,
  UNDERPAID,
  OVERPAID
}
