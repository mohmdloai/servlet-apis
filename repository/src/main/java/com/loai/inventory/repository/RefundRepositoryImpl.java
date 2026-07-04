package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.REFUND;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.RefundStatus;
import com.loai.inventory.domain.repository.RefundRepository;
import com.loai.inventory.repository.generated.tables.records.RefundRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RefundRepositoryImpl implements RefundRepository {

  private static final Logger log = LoggerFactory.getLogger(RefundRepositoryImpl.class);

  private final DSLContext dsl;

  public RefundRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(Refund refund) {
    dsl.insertInto(REFUND)
        .set(REFUND.ID, refund.getId())
        .set(REFUND.ORG_ID, refund.getOrgId())
        .set(REFUND.CUSTOMER_ID, refund.getCustomerId())
        .set(REFUND.CREDIT_NOTE_ID, refund.getCreditNoteId())
        .set(REFUND.PAYMENT_ID, refund.getPaymentId())
        .set(REFUND.AMOUNT, refund.getAmount())
        .set(REFUND.CURRENCY, refund.getCurrency())
        .set(
            REFUND.STATUS,
            com.loai.inventory.repository.generated.enums.RefundStatus.valueOf(
                refund.getStatus().name()))
        .set(REFUND.PAYMENT_TRANSACTION_ID, refund.getPaymentTransactionId())
        .set(
            REFUND.METHOD,
            com.loai.inventory.repository.generated.enums.PaymentProvider.valueOf(
                refund.getMethod().dbLiteral()))
        .set(REFUND.EXECUTED_AT, refund.getExecutedAt())
        .set(REFUND.CANCELLED_AT, refund.getCancelledAt())
        .set(REFUND.CANCELLED_REASON, refund.getCancelledReason())
        .set(REFUND.NOTES, refund.getNotes())
        .set(REFUND.CREATED_AT, refund.getCreatedAt())
        .set(REFUND.UPDATED_AT, refund.getUpdatedAt())
        .execute();
    log.debug(
        "Inserted refund id={} orgId={} amount={} status={} source={}",
        refund.getId(),
        refund.getOrgId(),
        refund.getAmount(),
        refund.getStatus(),
        refund.isCreditNoteBacked() ? "creditNote" : "payment");
  }

  @Override
  public Optional<Refund> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(REFUND)
        .where(REFUND.ORG_ID.eq(orgId).and(REFUND.ID.eq(id)))
        .fetchOptional()
        .map(this::toRefund);
  }

  @Override
  public Optional<Refund> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(REFUND)
        .where(REFUND.ORG_ID.eq(orgId).and(REFUND.ID.eq(id)))
        .forUpdate()
        .fetchOptional()
        .map(this::toRefund);
  }

  @Override
  public void updateExecution(Refund refund) {
    dsl.update(REFUND)
        .set(
            REFUND.STATUS,
            com.loai.inventory.repository.generated.enums.RefundStatus.valueOf(
                refund.getStatus().name()))
        .set(REFUND.PAYMENT_TRANSACTION_ID, refund.getPaymentTransactionId())
        .set(REFUND.EXECUTED_AT, refund.getExecutedAt())
        .set(REFUND.CANCELLED_AT, refund.getCancelledAt())
        .set(REFUND.CANCELLED_REASON, refund.getCancelledReason())
        .set(REFUND.UPDATED_AT, refund.getUpdatedAt())
        .where(REFUND.ID.eq(refund.getId()).and(REFUND.ORG_ID.eq(refund.getOrgId())))
        .execute();
  }

  @Override
  public BigDecimal sumExecutedByCreditNote(UUID orgId, UUID creditNoteId) {
    BigDecimal sum =
        dsl.select(DSL.coalesce(DSL.sum(REFUND.AMOUNT), BigDecimal.ZERO))
            .from(REFUND)
            .where(
                REFUND
                    .ORG_ID
                    .eq(orgId)
                    .and(REFUND.CREDIT_NOTE_ID.eq(creditNoteId))
                    .and(
                        REFUND.STATUS.eq(
                            com.loai.inventory.repository.generated.enums.RefundStatus.EXECUTED)))
            .fetchOne(0, BigDecimal.class);
    return sum == null ? BigDecimal.ZERO : sum;
  }

  @Override
  public boolean existsExecutedByCreditNote(UUID orgId, UUID creditNoteId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(REFUND)
            .where(
                REFUND
                    .ORG_ID
                    .eq(orgId)
                    .and(REFUND.CREDIT_NOTE_ID.eq(creditNoteId))
                    .and(
                        REFUND.STATUS.eq(
                            com.loai.inventory.repository.generated.enums.RefundStatus.EXECUTED))));
  }

  @Override
  public List<Refund> findByPaymentId(UUID orgId, UUID paymentId) {
    return dsl.selectFrom(REFUND)
        .where(REFUND.ORG_ID.eq(orgId).and(REFUND.PAYMENT_ID.eq(paymentId)))
        .orderBy(REFUND.CREATED_AT.asc(), REFUND.ID.asc())
        .fetch()
        .map(this::toRefund);
  }

  @Override
  public List<Refund> list(UUID orgId, RefundStatus status, int offset, int limit) {
    var query = dsl.selectFrom(REFUND).where(conditions(orgId, status));
    // Queue vs ledger: a status filter is a worklist — execute the oldest first (FIFO);
    // no filter is the audit ledger — newest first.
    var ordered =
        status != null
            ? query.orderBy(REFUND.CREATED_AT.asc(), REFUND.ID.asc())
            : query.orderBy(REFUND.CREATED_AT.desc(), REFUND.ID.desc());
    return ordered.offset(offset).limit(limit).fetch().map(this::toRefund);
  }

  @Override
  public long count(UUID orgId, RefundStatus status) {
    return dsl.fetchCount(dsl.selectFrom(REFUND).where(conditions(orgId, status)));
  }

  private static org.jooq.Condition conditions(UUID orgId, RefundStatus status) {
    org.jooq.Condition c = REFUND.ORG_ID.eq(orgId);
    if (status != null) {
      c =
          c.and(
              REFUND.STATUS.eq(
                  com.loai.inventory.repository.generated.enums.RefundStatus.valueOf(
                      status.name())));
    }
    return c;
  }

  private Refund toRefund(RefundRecord r) {
    return Refund.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getCustomerId(),
        r.getCreditNoteId(),
        r.getPaymentId(),
        r.getAmount(),
        r.getCurrency(),
        RefundStatus.valueOf(r.getStatus().name()),
        r.getPaymentTransactionId(),
        PaymentProvider.fromDbLiteral(r.getMethod().getLiteral()),
        r.getExecutedAt(),
        r.getCancelledAt(),
        r.getCancelledReason(),
        r.getNotes(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
