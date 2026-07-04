package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PAYMENT_ALLOCATION;

import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.repository.PaymentAllocationRepository;
import com.loai.inventory.repository.generated.tables.records.PaymentAllocationRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaymentAllocationRepositoryImpl implements PaymentAllocationRepository {

  private static final Logger log = LoggerFactory.getLogger(PaymentAllocationRepositoryImpl.class);

  private final DSLContext dsl;

  public PaymentAllocationRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(PaymentAllocation allocation) {
    dsl.insertInto(PAYMENT_ALLOCATION)
        .set(PAYMENT_ALLOCATION.ID, allocation.getId())
        .set(PAYMENT_ALLOCATION.ORG_ID, allocation.getOrgId())
        .set(PAYMENT_ALLOCATION.PAYMENT_ID, allocation.getPaymentId())
        .set(PAYMENT_ALLOCATION.SALES_INVOICE_ID, allocation.getSalesInvoiceId())
        .set(PAYMENT_ALLOCATION.AMOUNT, allocation.getAmount())
        .set(PAYMENT_ALLOCATION.RECEIVED_AT, allocation.getReceivedAt())
        .set(PAYMENT_ALLOCATION.CREATED_AT, allocation.getCreatedAt())
        .execute();
    log.debug(
        "Inserted payment_allocation id={} payment={} invoice={} amount={}",
        allocation.getId(),
        allocation.getPaymentId(),
        allocation.getSalesInvoiceId(),
        allocation.getAmount());
  }

  @Override
  public List<PaymentAllocation> findByInvoiceIdForUpdate(UUID orgId, UUID salesInvoiceId) {
    return dsl.selectFrom(PAYMENT_ALLOCATION)
        .where(
            PAYMENT_ALLOCATION
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT_ALLOCATION.SALES_INVOICE_ID.eq(salesInvoiceId)))
        .orderBy(PAYMENT_ALLOCATION.RECEIVED_AT.asc(), PAYMENT_ALLOCATION.ID.asc())
        .forUpdate()
        .fetch()
        .map(this::toAllocation);
  }

  @Override
  public List<PaymentAllocation> findByPaymentId(UUID orgId, UUID paymentId) {
    return dsl.selectFrom(PAYMENT_ALLOCATION)
        .where(PAYMENT_ALLOCATION.ORG_ID.eq(orgId).and(PAYMENT_ALLOCATION.PAYMENT_ID.eq(paymentId)))
        .orderBy(PAYMENT_ALLOCATION.RECEIVED_AT.asc(), PAYMENT_ALLOCATION.ID.asc())
        .fetch()
        .map(this::toAllocation);
  }

  private PaymentAllocation toAllocation(PaymentAllocationRecord r) {
    return PaymentAllocation.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getPaymentId(),
        r.getSalesInvoiceId(),
        r.getAmount(),
        r.getReceivedAt(),
        r.getCreatedAt());
  }
}
