package com.loai.inventory.service;

import org.jooq.exception.DataAccessException;

/**
 * Translates a document-number unique-constraint violation into a caller-actionable signal, so a
 * drifted counter surfaces as a clean 409 instead of an opaque "Unexpected error" 500. See {@code
 * stories/number_sequence_integrity.md}.
 *
 * <p>The runtime allocation path ({@code claimInvoiceNumber} / {@code claimCreditNoteNumber}) is
 * gapless and drift-proof against its own writes, but the {@code invoice_number} / {@code
 * credit_note_number} columns are plain columns that seed scripts, imports, or fixtures can
 * populate without advancing the counter. When that has happened, the next live issue mints a
 * number that is already taken and the {@code (org_id, …_number)} unique index rejects the insert.
 * We narrow to the exact number constraint so a <em>different</em> integrity violation is never
 * swallowed as this message.
 */
final class NumberSequenceConflicts {

  /** PostgreSQL SQLState for {@code unique_violation}. */
  private static final String UNIQUE_VIOLATION = "23505";

  private NumberSequenceConflicts() {}

  /**
   * True iff {@code e} is a unique-constraint violation on the named constraint. Walks the cause
   * chain because jOOQ wraps the driver's {@code SQLException} (whose message carries the
   * constraint name) inside its own {@link DataAccessException}.
   */
  static boolean isUniqueViolationOn(DataAccessException e, String constraintName) {
    if (!UNIQUE_VIOLATION.equals(e.sqlState())) {
      return false;
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message != null && message.contains(constraintName)) {
        return true;
      }
    }
    return false;
  }
}
