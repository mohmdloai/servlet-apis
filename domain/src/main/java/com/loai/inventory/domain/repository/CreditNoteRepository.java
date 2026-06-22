package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the CreditNote aggregate (note + lines) and the gapless per-org per-year
 * credit-note-number counter. Bound to a transactional {@code DSLContext} via {@link
 * CreditNoteRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface CreditNoteRepository {

  /** Insert the credit note + its lines in one go, inside the caller's txn. */
  void insert(CreditNote note, List<CreditNoteLine> lines);

  /** Read a credit note by id, scoped to {@code orgId}. */
  Optional<CreditNote> findById(UUID orgId, UUID id);

  /** Read a credit note by id with a write lock ({@code SELECT … FOR UPDATE}) for settle / void. */
  Optional<CreditNote> findByIdForUpdate(UUID orgId, UUID id);

  /** The note's lines, for response assembly. */
  List<CreditNoteLine> findLinesByCreditNoteId(UUID creditNoteId);

  /**
   * Persist the mutable cached state: {@code status}, {@code updated_at}. Scoped by {@code (org_id,
   * id)}.
   */
  void updateStatus(CreditNote note);

  /**
   * Sum the {@code total} of every live (ISSUED or SETTLED) credit note already raised against
   * {@code salesInvoiceId} — VOID notes excluded. Used to cap cumulative crediting at the invoice's
   * grand total. Returns {@code 0} when none exist.
   */
  java.math.BigDecimal sumIssuedTotalByInvoice(UUID orgId, UUID salesInvoiceId);

  /**
   * Claim the next gapless credit-note sequence number for {@code (orgId, year)} under a {@code
   * SELECT … FOR UPDATE} row lock — gapless, since the increment rolls back with the issuing
   * transaction. Returns the claimed value (first claim returns 1).
   */
  long claimCreditNoteNumber(UUID orgId, int year);
}
