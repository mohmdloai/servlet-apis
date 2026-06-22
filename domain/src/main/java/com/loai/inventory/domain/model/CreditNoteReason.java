package com.loai.inventory.domain.model;

/**
 * Why a {@link CreditNote} was issued — the authorization for a CreditNote-backed refund. Mirrors
 * the {@code credit_note_reason} DB enum (V25). Every reason still credits a specific prior {@link
 * SalesInvoice}; there is no "store credit with no purchase" reason in v1.
 */
public enum CreditNoteReason {
  RETURN,
  CANCELLATION,
  PRICING_ERROR,
  GOODWILL,
  DISPUTE_RESOLUTION
}
