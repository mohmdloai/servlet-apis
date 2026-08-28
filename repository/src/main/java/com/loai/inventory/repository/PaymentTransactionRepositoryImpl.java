package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.domain.model.PaymentDirection;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.repository.generated.tables.records.PaymentTransactionRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaymentTransactionRepositoryImpl implements PaymentTransactionRepository {

  private static final Logger log = LoggerFactory.getLogger(PaymentTransactionRepositoryImpl.class);

  private final DSLContext dsl;

  public PaymentTransactionRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Recorded insertIfAbsent(PaymentTransaction txn) {
    // INSERT … ON CONFLICT (provider, provider_ref) DO NOTHING RETURNING *. On conflict the
    // RETURNING
    // yields no row (DO NOTHING), so an empty Optional means the money event was already recorded.
    Optional<PaymentTransactionRecord> inserted =
        dsl.insertInto(PAYMENT_TRANSACTION)
            .set(PAYMENT_TRANSACTION.ID, txn.getId())
            .set(PAYMENT_TRANSACTION.ORG_ID, txn.getOrgId())
            .set(
                PAYMENT_TRANSACTION.PROVIDER,
                com.loai.inventory.repository.generated.enums.PaymentProvider.valueOf(
                    txn.getProvider().dbLiteral()))
            .set(PAYMENT_TRANSACTION.PROVIDER_REF, txn.getProviderRef())
            .set(
                PAYMENT_TRANSACTION.DIRECTION,
                com.loai.inventory.repository.generated.enums.PaymentDirection.valueOf(
                    txn.getDirection().name()))
            .set(PAYMENT_TRANSACTION.AMOUNT, txn.getAmount())
            .set(PAYMENT_TRANSACTION.CURRENCY, txn.getCurrency())
            .set(
                PAYMENT_TRANSACTION.VERIFICATION_STATUS,
                com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.valueOf(
                    txn.getVerificationStatus().name()))
            .set(PAYMENT_TRANSACTION.VERIFIED_BY, txn.getVerifiedBy())
            .set(PAYMENT_TRANSACTION.VERIFIED_AT, txn.getVerifiedAt())
            .set(PAYMENT_TRANSACTION.VERIFICATION_PROOF, txn.getVerificationProof())
            .set(PAYMENT_TRANSACTION.RECONCILIATION_STATUS, toReconRecord(txn))
            .set(PAYMENT_TRANSACTION.OCCURRED_AT, txn.getOccurredAt())
            .set(PAYMENT_TRANSACTION.RECORDED_AT, txn.getRecordedAt())
            .set(PAYMENT_TRANSACTION.CLAIMED_BY_CUSTOMER_ID, txn.getClaimedByCustomerId())
            .set(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID, txn.getClaimedSalesOrderId())
            .set(PAYMENT_TRANSACTION.CUSTOMER_NOTE, txn.getCustomerNote())
            .set(PAYMENT_TRANSACTION.PROOF_OBJECT_KEY, txn.getProofObjectKey())
            .set(PAYMENT_TRANSACTION.NOT_FOUND_REASON, txn.getNotFoundReason())
            .set(PAYMENT_TRANSACTION.NOT_FOUND_NOTE, txn.getNotFoundNote())
            .set(PAYMENT_TRANSACTION.RAW_PAYLOAD, toJsonb(txn.getRawPayload()))
            .onConflict(PAYMENT_TRANSACTION.PROVIDER, PAYMENT_TRANSACTION.PROVIDER_REF)
            .doNothing()
            .returning()
            .fetchOptional();

    if (inserted.isPresent()) {
      return new Recorded(toPaymentTransaction(inserted.get()), true);
    }

    PaymentTransaction existing =
        findByProviderRef(txn.getProvider(), txn.getProviderRef())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "ON CONFLICT DO NOTHING but no existing row for provider_ref "
                            + txn.getProviderRef()));
    log.debug(
        "payment_transaction provider_ref={} already recorded — idempotent no-op",
        txn.getProviderRef());
    return new Recorded(existing, false);
  }

  @Override
  public Optional<PaymentTransaction> findByProviderRef(
      PaymentProvider provider, String providerRef) {
    return dsl.selectFrom(PAYMENT_TRANSACTION)
        .where(
            PAYMENT_TRANSACTION
                .PROVIDER
                .eq(
                    com.loai.inventory.repository.generated.enums.PaymentProvider.valueOf(
                        provider.dbLiteral()))
                .and(PAYMENT_TRANSACTION.PROVIDER_REF.eq(providerRef)))
        .fetchOptional()
        .map(this::toPaymentTransaction);
  }

  @Override
  public Optional<PaymentTransaction> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(id).and(PAYMENT_TRANSACTION.ORG_ID.eq(orgId)))
        .forUpdate()
        .fetchOptional()
        .map(this::toPaymentTransaction);
  }

  @Override
  public void update(PaymentTransaction txn) {
    dsl.update(PAYMENT_TRANSACTION)
        .set(
            PAYMENT_TRANSACTION.VERIFICATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.valueOf(
                txn.getVerificationStatus().name()))
        .set(PAYMENT_TRANSACTION.VERIFIED_BY, txn.getVerifiedBy())
        .set(PAYMENT_TRANSACTION.VERIFIED_AT, txn.getVerifiedAt())
        .set(PAYMENT_TRANSACTION.VERIFICATION_PROOF, txn.getVerificationProof())
        .set(PAYMENT_TRANSACTION.RECONCILIATION_STATUS, toReconRecord(txn))
        // A claim's amount / occurred_at are the shopper's snapshot until the manager confirms the
        // bank's figures at verify time (PaymentTransaction.applyBankDetails); persisted here.
        .set(PAYMENT_TRANSACTION.AMOUNT, txn.getAmount())
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, txn.getOccurredAt())
        .set(PAYMENT_TRANSACTION.NOT_FOUND_REASON, txn.getNotFoundReason())
        .set(PAYMENT_TRANSACTION.NOT_FOUND_NOTE, txn.getNotFoundNote())
        .set(PAYMENT_TRANSACTION.RAW_PAYLOAD, toJsonb(txn.getRawPayload()))
        .set(PAYMENT_TRANSACTION.UPDATED_AT, txn.getUpdatedAt())
        .where(
            PAYMENT_TRANSACTION
                .ID
                .eq(txn.getId())
                .and(PAYMENT_TRANSACTION.ORG_ID.eq(txn.getOrgId())))
        .execute();
  }

  @Override
  public List<PaymentTransaction> list(UUID orgId, ListFilter filter, int offset, int limit) {
    if (filter != null && filter.isClaimsQueue()) {
      // The claims queue is ordered by what it can lose: the claimed order's clock. A claim on an
      // order that expires in 40 minutes outranks one filed an hour earlier on an order with a day
      // left. Orders without a hold (or claims that never named one) sink to the end, then FIFO.
      return dsl.select(PAYMENT_TRANSACTION.fields())
          .from(PAYMENT_TRANSACTION)
          .leftJoin(SALES_ORDER)
          .on(SALES_ORDER.ID.eq(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID))
          .where(conditions(orgId, filter))
          .orderBy(
              SALES_ORDER.EXPIRES_AT.asc().nullsLast(),
              PAYMENT_TRANSACTION.RECORDED_AT.asc(),
              PAYMENT_TRANSACTION.ID.asc())
          .offset(offset)
          .limit(limit)
          .fetch(r -> toPaymentTransaction(r.into(PAYMENT_TRANSACTION)));
    }
    var query = dsl.selectFrom(PAYMENT_TRANSACTION).where(conditions(orgId, filter));
    // Filtered = queue view, oldest first (FIFO worklist); unfiltered = ledger, newest first.
    var ordered =
        filter != null && !filter.isEmpty()
            ? query.orderBy(PAYMENT_TRANSACTION.OCCURRED_AT.asc(), PAYMENT_TRANSACTION.ID.asc())
            : query.orderBy(PAYMENT_TRANSACTION.RECORDED_AT.desc(), PAYMENT_TRANSACTION.ID.desc());
    return ordered.offset(offset).limit(limit).fetch().map(this::toPaymentTransaction);
  }

  @Override
  public long count(UUID orgId, ListFilter filter) {
    return dsl.fetchCount(dsl.selectFrom(PAYMENT_TRANSACTION).where(conditions(orgId, filter)));
  }

  @Override
  public Optional<PaymentTransaction> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(id).and(PAYMENT_TRANSACTION.ORG_ID.eq(orgId)))
        .fetchOptional()
        .map(this::toPaymentTransaction);
  }

  @Override
  public List<PaymentTransaction> findOpenClaimsByOrder(UUID orgId, UUID salesOrderId) {
    // Served by idx_txn_claim_open (V90): the partial index over exactly these two states.
    return dsl.selectFrom(PAYMENT_TRANSACTION)
        .where(
            PAYMENT_TRANSACTION
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID.eq(salesOrderId))
                .and(PAYMENT_TRANSACTION.VERIFICATION_STATUS.in(OPEN_CLAIM_STATES)))
        .orderBy(PAYMENT_TRANSACTION.RECORDED_AT.asc(), PAYMENT_TRANSACTION.ID.asc())
        .fetch()
        .map(this::toPaymentTransaction);
  }

  @Override
  public int abandonOpenClaims(UUID salesOrderId, UUID exceptTransactionId, OffsetDateTime now) {
    Condition target =
        PAYMENT_TRANSACTION
            .CLAIMED_SALES_ORDER_ID
            .eq(salesOrderId)
            .and(PAYMENT_TRANSACTION.VERIFICATION_STATUS.in(OPEN_CLAIM_STATES));
    if (exceptTransactionId != null) {
      target = target.and(PAYMENT_TRANSACTION.ID.ne(exceptTransactionId));
    }
    // The status predicate IS the guard: a sibling verified concurrently no longer matches.
    return dsl.update(PAYMENT_TRANSACTION)
        .set(
            PAYMENT_TRANSACTION.VERIFICATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.ABANDONED)
        .set(PAYMENT_TRANSACTION.UPDATED_AT, now)
        .where(target)
        .execute();
  }

  @Override
  public Optional<PaymentTransaction> findLatestClaimByOrder(UUID salesOrderId) {
    return dsl.selectFrom(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID.eq(salesOrderId))
        .orderBy(PAYMENT_TRANSACTION.RECORDED_AT.desc(), PAYMENT_TRANSACTION.ID.desc())
        .limit(1)
        .fetchOptional()
        .map(this::toPaymentTransaction);
  }

  private static final List<com.loai.inventory.repository.generated.enums.PaymentVerificationStatus>
      OPEN_CLAIM_STATES =
          List.of(
              com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.UNVERIFIED,
              com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.NOT_FOUND);

  private static Condition conditions(UUID orgId, ListFilter filter) {
    Condition c = PAYMENT_TRANSACTION.ORG_ID.eq(orgId);
    if (filter == null) {
      return c;
    }
    if (filter.verificationStatus() != null) {
      c =
          c.and(
              PAYMENT_TRANSACTION.VERIFICATION_STATUS.eq(
                  com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.valueOf(
                      filter.verificationStatus().name())));
    }
    if (filter.reconciliationStatus() != null) {
      c =
          c.and(
              PAYMENT_TRANSACTION.RECONCILIATION_STATUS.eq(
                  com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus.valueOf(
                      filter.reconciliationStatus().name())));
    }
    if (filter.hasPayment() != null) {
      Condition payment =
          DSL.exists(
              DSL.selectOne()
                  .from(PAYMENT)
                  .where(PAYMENT.PAYMENT_TRANSACTION_ID.eq(PAYMENT_TRANSACTION.ID)));
      c = c.and(filter.hasPayment() ? payment : DSL.not(payment));
    }
    if (filter.provider() != null) {
      c =
          c.and(
              PAYMENT_TRANSACTION.PROVIDER.eq(
                  com.loai.inventory.repository.generated.enums.PaymentProvider.valueOf(
                      filter.provider().dbLiteral())));
    }
    if (filter.providerRef() != null) {
      c = c.and(PAYMENT_TRANSACTION.PROVIDER_REF.eq(filter.providerRef()));
    }
    if (filter.claimedSalesOrderId() != null) {
      c = c.and(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID.eq(filter.claimedSalesOrderId()));
    }
    return c;
  }

  private static JSONB toJsonb(String json) {
    return json == null ? null : JSONB.jsonb(json);
  }

  private static com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus
      toReconRecord(PaymentTransaction txn) {
    return txn.getReconciliationStatus() == null
        ? null
        : com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus.valueOf(
            txn.getReconciliationStatus().name());
  }

  private PaymentTransaction toPaymentTransaction(PaymentTransactionRecord r) {
    return PaymentTransaction.rehydrate(
        r.getId(),
        r.getOrgId(),
        PaymentProvider.fromDbLiteral(r.getProvider().getLiteral()),
        r.getProviderRef(),
        PaymentDirection.valueOf(r.getDirection().name()),
        r.getAmount(),
        r.getCurrency(),
        r.getOccurredAt(),
        r.getRecordedAt(),
        r.getClaimedByCustomerId(),
        r.getClaimedSalesOrderId(),
        r.getCustomerNote(),
        r.getProofObjectKey(),
        r.getCreatedAt(),
        PaymentVerificationStatus.valueOf(r.getVerificationStatus().name()),
        r.getVerifiedBy(),
        r.getVerifiedAt(),
        r.getVerificationProof(),
        r.getNotFoundReason(),
        r.getNotFoundNote(),
        r.getRawPayload() == null ? null : r.getRawPayload().data(),
        r.getReconciliationStatus() == null
            ? null
            : PaymentReconciliationStatus.valueOf(r.getReconciliationStatus().name()),
        r.getUpdatedAt());
  }
}
