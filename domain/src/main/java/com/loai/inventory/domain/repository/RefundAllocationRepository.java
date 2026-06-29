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

  /**
   * Whether any RefundAllocation exists against an allocation of the given payment — i.e. a
   * CreditNote-backed refund has executed against this payment. Used to block upholding a dispute
   * after a dispute-resolution refund has begun: such a row can only appear post-dispute, because
   * an allocation-backed refund moves a payment off ALLOCATED before it could ever be disputed.
   */
  boolean existsForPayment(UUID orgId, UUID paymentId);
}
