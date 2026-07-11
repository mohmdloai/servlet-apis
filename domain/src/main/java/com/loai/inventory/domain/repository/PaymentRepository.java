package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

  /** Read a payment by id without locking. Used by the read-only GET endpoint. */
  Optional<Payment> findById(UUID orgId, UUID id);

  /**
   * Read a payment by id with a write lock ({@code SELECT … FOR UPDATE}). Used by refund execution
   * to serialize concurrent refunds touching the same payment's caches.
   */
  Optional<Payment> findByIdForUpdate(UUID orgId, UUID id);

  /**
   * All payments ever applied to an order, regardless of status (RECEIVED, ALLOCATED, DISPUTED,
   * REFUNDED), ordered {@code received_at ASC, id ASC} — the same FIFO order invoice allocation
   * consumes them in, so the list reads as the audit trail of {@code prepaid_amount}. Unlocked,
   * unfiltered sibling of {@link #findUnallocatedByOrderForUpdate} for the read-only order money
   * story.
   */
  List<Payment> findByOrderId(UUID orgId, UUID salesOrderId);

  /**
   * The org's payments, optionally narrowed by {@code status} (null = any) and to those still
   * carrying an unallocated balance ({@code unallocatedOnly} → {@code unallocated_amount > 0}) —
   * one page of the tenant's payments worklist / ledger ({@code stories/org_health_rollup.md}).
   * Queue-vs-ledger ordering like the other lists: a filter present → oldest-first ({@code
   * received_at ASC, id ASC}, the allocation FIFO); no filter → newest-first ({@code received_at
   * DESC, id DESC}). {@code status=DISPUTED} backs the disputes preview, {@code unallocatedOnly}
   * the unallocated preview — the exact predicates behind the health rollup's counts.
   */
  List<Payment> list(
      UUID orgId, PaymentStatus status, boolean unallocatedOnly, int offset, int limit);

  /**
   * Total matching {@link #list} under the same {@code status} / {@code unallocatedOnly} filter.
   */
  long count(UUID orgId, PaymentStatus status, boolean unallocatedOnly);

  /**
   * Batch projection {@code payment id → sales_order_id} for the given payments, scoped to {@code
   * orgId} — one query decorates a whole worklist page (mirrors {@code
   * SalesOrderRepository#findOrderNumbersByIds}). Payments not in {@code orgId} and <em>orphan</em>
   * payments ({@code sales_order_id IS NULL}) are simply absent from the map.
   */
  Map<UUID, UUID> findOrderIdsByIds(UUID orgId, Collection<UUID> paymentIds);

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

  /**
   * Persist the dispute-mutable state of a payment: {@code status}, {@code disputed_at}, {@code
   * dispute_reason}, {@code updated_at}. Scoped by {@code (org_id, id)}.
   */
  void updateDisputeState(Payment payment);
}
