package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.REFUND_ALLOCATION;

import com.loai.inventory.domain.model.RefundAllocation;
import com.loai.inventory.domain.repository.RefundAllocationRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class RefundAllocationRepositoryImpl implements RefundAllocationRepository {

  private final DSLContext dsl;

  public RefundAllocationRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(RefundAllocation allocation) {
    dsl.insertInto(REFUND_ALLOCATION)
        .set(REFUND_ALLOCATION.ID, allocation.getId())
        .set(REFUND_ALLOCATION.REFUND_ID, allocation.getRefundId())
        .set(REFUND_ALLOCATION.PAYMENT_ALLOCATION_ID, allocation.getPaymentAllocationId())
        .set(REFUND_ALLOCATION.AMOUNT, allocation.getAmount())
        .execute();
  }

  @Override
  public BigDecimal sumByPaymentAllocation(UUID paymentAllocationId) {
    // refund_allocation rows are written only when a refund executes, so summing them is equivalent
    // to "already refunded against this allocation".
    BigDecimal sum =
        dsl.select(DSL.coalesce(DSL.sum(REFUND_ALLOCATION.AMOUNT), BigDecimal.ZERO))
            .from(REFUND_ALLOCATION)
            .where(REFUND_ALLOCATION.PAYMENT_ALLOCATION_ID.eq(paymentAllocationId))
            .fetchOne(0, BigDecimal.class);
    return sum == null ? BigDecimal.ZERO : sum;
  }
}
