package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Refund;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Refund}. Bound to a transactional {@code DSLContext} via {@link
 * RefundRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface RefundRepository {

  /** Insert a new PENDING refund row. */
  void insert(Refund refund);

  /** Read a refund by id, scoped to {@code orgId}. */
  Optional<Refund> findById(UUID orgId, UUID id);

  /** Read a refund by id with a write lock ({@code SELECT … FOR UPDATE}) for execute / cancel. */
  Optional<Refund> findByIdForUpdate(UUID orgId, UUID id);

  /**
   * Persist the mutable state of a refund after execute / cancel: {@code status}, {@code
   * payment_transaction_id}, {@code executed_at}, {@code cancelled_at}, {@code cancelled_reason},
   * {@code updated_at}. Scoped by {@code (org_id, id)}.
   */
  void updateExecution(Refund refund);

  /** Total amount of EXECUTED refunds against a credit note — drives the SETTLED threshold. */
  BigDecimal sumExecutedByCreditNote(UUID orgId, UUID creditNoteId);

  /** True if any EXECUTED refund references this credit note — blocks voiding it. */
  boolean existsExecutedByCreditNote(UUID orgId, UUID creditNoteId);
}
