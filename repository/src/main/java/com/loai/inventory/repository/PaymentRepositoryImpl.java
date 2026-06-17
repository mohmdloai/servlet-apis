package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PAYMENT;

import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentStatus;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.repository.generated.tables.records.PaymentRecord;
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
        PaymentStatus.valueOf(r.getStatus().name()),
        r.getNotes(),
        r.getUpdatedAt());
  }
}
