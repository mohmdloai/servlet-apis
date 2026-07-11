package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PAYMENT;

import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentStatus;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.repository.generated.tables.records.PaymentRecord;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaymentRepositoryImpl implements PaymentRepository {

  private static final Logger log = LoggerFactory.getLogger(PaymentRepositoryImpl.class);

  private final DSLContext dsl;

  public PaymentRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(Payment payment) {
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, payment.getId())
        .set(PAYMENT.ORG_ID, payment.getOrgId())
        .set(PAYMENT.CUSTOMER_ID, payment.getCustomerId())
        .set(PAYMENT.SALES_ORDER_ID, payment.getSalesOrderId())
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, payment.getPaymentTransactionId())
        .set(PAYMENT.AMOUNT, payment.getAmount())
        .set(PAYMENT.CURRENCY, payment.getCurrency())
        .set(PAYMENT.UNALLOCATED_AMOUNT, payment.getUnallocatedAmount())
        .set(PAYMENT.REFUNDED_AMOUNT, payment.getRefundedAmount())
        .set(
            PAYMENT.STATUS,
            com.loai.inventory.repository.generated.enums.PaymentStatus.valueOf(
                payment.getStatus().name()))
        .set(PAYMENT.RECEIVED_AT, payment.getReceivedAt())
        .set(PAYMENT.NOTES, payment.getNotes())
        .execute();
    log.debug(
        "Inserted payment id={} orderId={} amount={} status={}",
        payment.getId(),
        payment.getSalesOrderId(),
        payment.getAmount(),
        payment.getStatus());
  }

  @Override
  public Optional<Payment> findByTransactionId(UUID orgId, UUID paymentTransactionId) {
    return dsl.selectFrom(PAYMENT)
        .where(
            PAYMENT.ORG_ID.eq(orgId).and(PAYMENT.PAYMENT_TRANSACTION_ID.eq(paymentTransactionId)))
        .fetchOptional()
        .map(this::toPayment);
  }

  @Override
  public Optional<Payment> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(PAYMENT)
        .where(PAYMENT.ORG_ID.eq(orgId).and(PAYMENT.ID.eq(id)))
        .fetchOptional()
        .map(this::toPayment);
  }

  @Override
  public Optional<Payment> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(PAYMENT)
        .where(PAYMENT.ORG_ID.eq(orgId).and(PAYMENT.ID.eq(id)))
        .forUpdate()
        .fetchOptional()
        .map(this::toPayment);
  }

  @Override
  public Map<UUID, UUID> findOrderIdsByIds(UUID orgId, Collection<UUID> paymentIds) {
    if (paymentIds.isEmpty()) {
      return Map.of();
    }
    return dsl.select(PAYMENT.ID, PAYMENT.SALES_ORDER_ID)
        .from(PAYMENT)
        .where(
            PAYMENT
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT.ID.in(paymentIds))
                .and(PAYMENT.SALES_ORDER_ID.isNotNull()))
        .fetchMap(PAYMENT.ID, PAYMENT.SALES_ORDER_ID);
  }

  @Override
  public List<Payment> findByOrderId(UUID orgId, UUID salesOrderId) {
    return dsl.selectFrom(PAYMENT)
        .where(PAYMENT.ORG_ID.eq(orgId).and(PAYMENT.SALES_ORDER_ID.eq(salesOrderId)))
        // id is the stable tiebreaker when two payments share a received_at millisecond.
        .orderBy(PAYMENT.RECEIVED_AT.asc(), PAYMENT.ID.asc())
        .fetch()
        .map(this::toPayment);
  }

  @Override
  public List<Payment> list(
      UUID orgId, PaymentStatus status, boolean unallocatedOnly, int offset, int limit) {
    var query = dsl.selectFrom(PAYMENT).where(conditions(orgId, status, unallocatedOnly));
    // Queue vs ledger: a filter is a worklist (oldest-first, the allocation FIFO); the bare list is
    // the ledger (newest-first). Mirrors RefundRepositoryImpl / SalesInvoiceRepositoryImpl#list.
    boolean filtered = status != null || unallocatedOnly;
    var ordered =
        filtered
            ? query.orderBy(PAYMENT.RECEIVED_AT.asc(), PAYMENT.ID.asc())
            : query.orderBy(PAYMENT.RECEIVED_AT.desc(), PAYMENT.ID.desc());
    return ordered.offset(offset).limit(limit).fetch().map(this::toPayment);
  }

  @Override
  public long count(UUID orgId, PaymentStatus status, boolean unallocatedOnly) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT).where(conditions(orgId, status, unallocatedOnly)));
  }

  private static org.jooq.Condition conditions(
      UUID orgId, PaymentStatus status, boolean unallocatedOnly) {
    org.jooq.Condition c = PAYMENT.ORG_ID.eq(orgId);
    if (status != null) {
      c =
          c.and(
              PAYMENT.STATUS.eq(
                  com.loai.inventory.repository.generated.enums.PaymentStatus.valueOf(
                      status.name())));
    }
    if (unallocatedOnly) {
      c = c.and(PAYMENT.UNALLOCATED_AMOUNT.gt(java.math.BigDecimal.ZERO));
    }
    return c;
  }

  @Override
  public List<Payment> findUnallocatedByOrderForUpdate(UUID orgId, UUID salesOrderId) {
    return dsl.selectFrom(PAYMENT)
        .where(
            PAYMENT
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT.SALES_ORDER_ID.eq(salesOrderId))
                .and(PAYMENT.UNALLOCATED_AMOUNT.gt(java.math.BigDecimal.ZERO))
                .and(
                    PAYMENT.STATUS.notIn(
                        com.loai.inventory.repository.generated.enums.PaymentStatus.REFUNDED,
                        com.loai.inventory.repository.generated.enums.PaymentStatus.DISPUTED)))
        // id is the stable tiebreaker when two payments share a received_at millisecond.
        .orderBy(PAYMENT.RECEIVED_AT.asc(), PAYMENT.ID.asc())
        .forUpdate()
        .fetch()
        .map(this::toPayment);
  }

  @Override
  public void updateAllocationState(Payment payment) {
    dsl.update(PAYMENT)
        .set(PAYMENT.UNALLOCATED_AMOUNT, payment.getUnallocatedAmount())
        .set(PAYMENT.REFUNDED_AMOUNT, payment.getRefundedAmount())
        .set(
            PAYMENT.STATUS,
            com.loai.inventory.repository.generated.enums.PaymentStatus.valueOf(
                payment.getStatus().name()))
        .set(PAYMENT.UPDATED_AT, payment.getUpdatedAt())
        .where(PAYMENT.ID.eq(payment.getId()).and(PAYMENT.ORG_ID.eq(payment.getOrgId())))
        .execute();
  }

  @Override
  public void updateDisputeState(Payment payment) {
    dsl.update(PAYMENT)
        .set(
            PAYMENT.STATUS,
            com.loai.inventory.repository.generated.enums.PaymentStatus.valueOf(
                payment.getStatus().name()))
        .set(PAYMENT.DISPUTED_AT, payment.getDisputedAt())
        .set(PAYMENT.DISPUTE_REASON, payment.getDisputeReason())
        .set(PAYMENT.UPDATED_AT, payment.getUpdatedAt())
        .where(PAYMENT.ID.eq(payment.getId()).and(PAYMENT.ORG_ID.eq(payment.getOrgId())))
        .execute();
  }

  private Payment toPayment(PaymentRecord r) {
    return Payment.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getCustomerId(),
        r.getSalesOrderId(),
        r.getPaymentTransactionId(),
        r.getAmount(),
        r.getCurrency(),
        r.getReceivedAt(),
        r.getCreatedAt(),
        r.getUnallocatedAmount(),
        r.getRefundedAmount(),
        PaymentStatus.valueOf(r.getStatus().name()),
        r.getNotes(),
        r.getDisputedAt(),
        r.getDisputeReason(),
        r.getUpdatedAt());
  }
}
