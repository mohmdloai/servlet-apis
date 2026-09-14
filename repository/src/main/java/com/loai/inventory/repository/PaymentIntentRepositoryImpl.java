package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PAYMENT_INTENT;

import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.repository.PaymentIntentRepository;
import com.loai.inventory.repository.generated.tables.records.PaymentIntentRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class PaymentIntentRepositoryImpl implements PaymentIntentRepository {

  private final DSLContext dsl;

  public PaymentIntentRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(PaymentIntent intent) {
    dsl.insertInto(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.ID, intent.getId())
        .set(PAYMENT_INTENT.ORG_ID, intent.getOrgId())
        .set(PAYMENT_INTENT.SALES_ORDER_ID, intent.getSalesOrderId())
        .set(
            PAYMENT_INTENT.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.valueOf(
                intent.getProvider().dbLiteral()))
        .set(PAYMENT_INTENT.AMOUNT, intent.getAmount())
        .set(PAYMENT_INTENT.CURRENCY, intent.getCurrency())
        .set(PAYMENT_INTENT.INTENTION_ID, intent.getIntentionId())
        .set(PAYMENT_INTENT.PAYMOB_ORDER_ID, intent.getPaymobOrderId())
        .set(PAYMENT_INTENT.CLIENT_SECRET, intent.getClientSecret())
        .set(PAYMENT_INTENT.SPECIAL_REFERENCE, intent.getSpecialReference())
        .set(PAYMENT_INTENT.STATUS, intent.getStatus().name())
        .set(PAYMENT_INTENT.SETTLED_TXN_REF, intent.getSettledTxnRef())
        .set(PAYMENT_INTENT.EXPIRES_AT, intent.getExpiresAt())
        .set(PAYMENT_INTENT.CREATED_AT, intent.getCreatedAt())
        .set(PAYMENT_INTENT.UPDATED_AT, intent.getUpdatedAt())
        .execute();
  }

  @Override
  public Optional<PaymentIntent> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(PAYMENT_INTENT)
        .where(PAYMENT_INTENT.ORG_ID.eq(orgId).and(PAYMENT_INTENT.ID.eq(id)))
        .fetchOptional()
        .map(PaymentIntentRepositoryImpl::toModel);
  }

  @Override
  public Optional<PaymentIntent> findBySpecialReference(UUID orgId, String specialReference) {
    if (specialReference == null || specialReference.isBlank()) {
      return Optional.empty();
    }
    return dsl.selectFrom(PAYMENT_INTENT)
        .where(
            PAYMENT_INTENT
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT_INTENT.SPECIAL_REFERENCE.eq(specialReference)))
        .fetchOptional()
        .map(PaymentIntentRepositoryImpl::toModel);
  }

  @Override
  public Optional<PaymentIntent> findLiveForOrder(
      UUID orgId, UUID salesOrderId, BigDecimal amount, String currency, OffsetDateTime now) {
    return dsl.selectFrom(PAYMENT_INTENT)
        .where(
            PAYMENT_INTENT
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT_INTENT.SALES_ORDER_ID.eq(salesOrderId))
                .and(PAYMENT_INTENT.STATUS.eq(PaymentIntent.Status.PENDING.name()))
                .and(PAYMENT_INTENT.EXPIRES_AT.gt(now))
                .and(PAYMENT_INTENT.AMOUNT.eq(amount))
                .and(PAYMENT_INTENT.CURRENCY.eq(currency)))
        .orderBy(PAYMENT_INTENT.CREATED_AT.desc(), PAYMENT_INTENT.ID.desc())
        .limit(1)
        .fetchOptional()
        .map(PaymentIntentRepositoryImpl::toModel);
  }

  @Override
  public void update(PaymentIntent intent) {
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.INTENTION_ID, intent.getIntentionId())
        .set(PAYMENT_INTENT.PAYMOB_ORDER_ID, intent.getPaymobOrderId())
        .set(PAYMENT_INTENT.CLIENT_SECRET, intent.getClientSecret())
        .set(PAYMENT_INTENT.STATUS, intent.getStatus().name())
        .set(PAYMENT_INTENT.SETTLED_TXN_REF, intent.getSettledTxnRef())
        .set(PAYMENT_INTENT.UPDATED_AT, intent.getUpdatedAt())
        .where(PAYMENT_INTENT.ID.eq(intent.getId()))
        .execute();
  }

  @Override
  public List<PaymentIntent> findPendingCreatedBefore(OffsetDateTime cutoff, int limit) {
    return dsl.selectFrom(PAYMENT_INTENT)
        .where(
            PAYMENT_INTENT
                .STATUS
                .eq(PaymentIntent.Status.PENDING.name())
                .and(PAYMENT_INTENT.CREATED_AT.lt(cutoff)))
        .orderBy(PAYMENT_INTENT.CREATED_AT.asc(), PAYMENT_INTENT.ID.asc())
        .limit(limit)
        .fetch()
        .map(PaymentIntentRepositoryImpl::toModel);
  }

  @Override
  public long countStuck(UUID orgId, OffsetDateTime now) {
    return dsl.fetchCount(
        PAYMENT_INTENT,
        PAYMENT_INTENT
            .ORG_ID
            .eq(orgId)
            .and(PAYMENT_INTENT.STATUS.eq(PaymentIntent.Status.PENDING.name()))
            .and(PAYMENT_INTENT.EXPIRES_AT.lt(now)));
  }

  @Override
  public int expireLiveForOrg(UUID orgId, OffsetDateTime now) {
    return dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.STATUS, PaymentIntent.Status.EXPIRED.name())
        .set(PAYMENT_INTENT.UPDATED_AT, now)
        .where(
            PAYMENT_INTENT
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT_INTENT.STATUS.eq(PaymentIntent.Status.PENDING.name())))
        .execute();
  }

  private static PaymentIntent toModel(PaymentIntentRecord r) {
    return PaymentIntent.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getSalesOrderId(),
        PaymentProvider.fromDbLiteral(r.getProvider().getLiteral()),
        r.getAmount(),
        r.getCurrency(),
        r.getSpecialReference(),
        r.getExpiresAt(),
        r.getCreatedAt(),
        r.getIntentionId(),
        r.getPaymobOrderId(),
        r.getClientSecret(),
        PaymentIntent.Status.valueOf(r.getStatus()),
        r.getSettledTxnRef(),
        r.getUpdatedAt());
  }
}
