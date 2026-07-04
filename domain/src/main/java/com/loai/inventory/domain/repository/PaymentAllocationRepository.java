package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentAllocation;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link PaymentAllocation} rows. Bound to a transactional {@code DSLContext} via
 * {@link PaymentAllocationRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface PaymentAllocationRepository {

  /** Insert one immutable allocation row. */
  void insert(PaymentAllocation allocation);

  /**
   * The allocations applied to a given invoice, locked {@code FOR UPDATE} and ordered FIFO ({@code
   * created_at ASC, id ASC}) for deterministic unwinding by a CreditNote-backed refund.
   */
  List<PaymentAllocation> findByInvoiceIdForUpdate(UUID orgId, UUID salesInvoiceId);

  /**
   * The allocations a given payment was consumed by, unlocked and ordered FIFO ({@code received_at
   * ASC, id ASC} — the order they were applied in). The read-only sibling of {@link
   * #findByInvoiceIdForUpdate} for the payment detail read: which invoices this payment funded.
   */
  List<PaymentAllocation> findByPaymentId(UUID orgId, UUID paymentId);
}
