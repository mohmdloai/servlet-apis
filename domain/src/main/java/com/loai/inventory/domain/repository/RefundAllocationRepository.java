package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.RefundAllocation;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persistence for {@link RefundAllocation} rows. Bound to a transactional {@code DSLContext} via
 * {@link RefundAllocationRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface RefundAllocationRepository {

  /** Insert one immutable refund-allocation row. */
  void insert(RefundAllocation allocation);

  /**
   * Total already refunded against a given {@code payment_allocation} (sum over EXECUTED refunds'
   * rows). The remaining unwindable amount on an allocation is {@code allocation.amount} minus
   * this.
   */
  BigDecimal sumByPaymentAllocation(UUID paymentAllocationId);
}
