package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Payment}. Bound to a transactional {@code DSLContext} via {@link
 * PaymentRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface PaymentRepository {

  /** Insert a new payment row. */
  void insert(Payment payment);

  /**
   * Find the payment created from a given transaction ({@code payment.payment_transaction_id} is
   * {@code UNIQUE}). Used for idempotent replay of a verify call.
   */
  Optional<Payment> findByTransactionId(UUID orgId, UUID paymentTransactionId);

  /**
   * The order's prepayment Payments still carrying an unallocated balance, locked {@code FOR
   * UPDATE} and ordered for FIFO consumption at invoice issuance:
   *
   * <pre>
   *   WHERE sales_order_id = :order AND unallocated_amount > 0
   *     AND status NOT IN ('REFUNDED','DISPUTED')
   *   ORDER BY received_at ASC, id ASC   -- id is the stable tiebreaker
   *   FOR UPDATE
   * </pre>
   *
   * The lock serializes concurrent allocators of the same order's money. See {@code
   * sys-analysis/outbound/paymentAllocation.md} (line 184).
   */
  List<Payment> findUnallocatedByOrderForUpdate(UUID orgId, UUID salesOrderId);

  /**
   * Persist the allocation-mutable state of a payment: {@code unallocated_amount}, {@code status},
   * {@code updated_at}. Scoped by {@code (org_id, id)}.
   */
  void updateAllocationState(Payment payment);
}
