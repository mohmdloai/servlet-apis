package com.loai.inventory.domain.model;

/**
 * Lifecycle of a {@link CreditNote}: DRAFT → ISSUED → SETTLED, with a VOID branch off ISSUED.
 * Mirrors the {@code credit_note_status} DB enum (V25). SETTLED is reached once executed refunds
 * cover the note's total; VOID is allowed only while no refund has been EXECUTED against it.
 */
public enum CreditNoteStatus {
  DRAFT,
  ISSUED,
  SETTLED,
  VOID
}
