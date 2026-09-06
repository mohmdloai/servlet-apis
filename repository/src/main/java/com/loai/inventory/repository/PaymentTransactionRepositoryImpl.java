package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.PaymentDirection;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.repository.generated.tables.records.PaymentTransactionRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SortField;
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
            .set(PAYMENT_TRANSACTION.CASH_SHIFT_ID, txn.getCashShiftId())
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
        .set(PAYMENT_TRANSACTION.CASH_SHIFT_ID, txn.getCashShiftId())
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
    return query
        .orderBy(order(filter))
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toPaymentTransaction);
  }

  /**
   * A state queue reads oldest first (FIFO worklist); the ledger reads newest first. An explicit
   * {@code sort} overrides both on {@code occurred_at} — the time the row shows — and the narrowing
   * dimensions (method, window, band, search) never change the order on their own ({@code
   * stories/transaction_filters.md}). {@code id} tiebreaks keep pagination deterministic.
   */
  private static List<SortField<?>> order(ListFilter filter) {
    if (filter != null && filter.sort() != null) {
      return switch (filter.sort()) {
        case NEWEST ->
            List.of(PAYMENT_TRANSACTION.OCCURRED_AT.desc(), PAYMENT_TRANSACTION.ID.desc());
        case OLDEST -> List.of(PAYMENT_TRANSACTION.OCCURRED_AT.asc(), PAYMENT_TRANSACTION.ID.asc());
      };
    }
    return filter != null && filter.isQueue()
        ? List.of(PAYMENT_TRANSACTION.OCCURRED_AT.asc(), PAYMENT_TRANSACTION.ID.asc())
        : List.of(PAYMENT_TRANSACTION.RECORDED_AT.desc(), PAYMENT_TRANSACTION.ID.desc());
  }

  @Override
  public long count(UUID orgId, ListFilter filter) {
    return dsl.fetchCount(dsl.selectFrom(PAYMENT_TRANSACTION).where(conditions(orgId, filter)));
  }

  @Override
  public ListStats stats(UUID orgId, ListFilter filter) {
    // One pass over the filtered set: the count the pager needs and the two money figures the
    // ledger line shows. Only VERIFIED rows are money — a shopper's claim resting UNVERIFIED (or
    // NOT_FOUND / ABANDONED) is a row in the ledger, not money that arrived.
    Field<BigDecimal> zero = DSL.inline(BigDecimal.ZERO);
    Condition verified =
        PAYMENT_TRANSACTION.VERIFICATION_STATUS.eq(
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.VERIFIED);
    Condition credit =
        PAYMENT_TRANSACTION.DIRECTION.eq(
            com.loai.inventory.repository.generated.enums.PaymentDirection.CREDIT);
    Condition debit =
        PAYMENT_TRANSACTION.DIRECTION.eq(
            com.loai.inventory.repository.generated.enums.PaymentDirection.DEBIT);
    Field<BigDecimal> moneyIn =
        DSL.sum(DSL.when(verified.and(credit), PAYMENT_TRANSACTION.AMOUNT).otherwise(zero));
    Field<BigDecimal> moneyOut =
        DSL.sum(DSL.when(verified.and(debit), PAYMENT_TRANSACTION.AMOUNT).otherwise(zero));
    var row =
        dsl.select(DSL.count(), moneyIn, moneyOut)
            .from(PAYMENT_TRANSACTION)
            .where(conditions(orgId, filter))
            .fetchOne();
    if (row == null) {
      return ListStats.empty();
    }
    return new ListStats(row.value1().longValue(), money(row.value2()), money(row.value3()));
  }

  private static BigDecimal money(BigDecimal sum) {
    return (sum == null ? BigDecimal.ZERO : sum).setScale(2, RoundingMode.HALF_EVEN);
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
    if (filter.q() != null) {
      c = c.and(searchLegs(orgId, filter.q()));
    }
    if (filter.occurredFrom() != null) {
      c = c.and(PAYMENT_TRANSACTION.OCCURRED_AT.ge(filter.occurredFrom()));
    }
    if (filter.occurredTo() != null) {
      c = c.and(PAYMENT_TRANSACTION.OCCURRED_AT.lt(filter.occurredTo()));
    }
    if (filter.minAmount() != null) {
      c = c.and(PAYMENT_TRANSACTION.AMOUNT.ge(filter.minAmount()));
    }
    if (filter.maxAmount() != null) {
      c = c.and(PAYMENT_TRANSACTION.AMOUNT.le(filter.maxAmount()));
    }
    return c;
  }

  /**
   * The {@code q} legs ({@code stories/transaction_filters.md}), ORed:
   *
   * <ul>
   *   <li><b>reference</b> — {@code provider_ref ILIKE '%q%'}: the support question "did this
   *       transfer arrive?" asked with the four digits the customer read out, not the exact string
   *       the {@code provider_ref=} lookup needs.
   *   <li><b>order</b> — the linked order's number by fragment, where the link is the 1:1 payment's
   *       {@code sales_order_id} (a matched, underpaid or overpaid transaction, or a resolved
   *       orphan) or the row's own {@code claimed_sales_order_id} (a shopper's claim).
   *   <li><b>customer</b> — that order's customer by folded name ({@code fold_search} on both
   *       sides, the orders search's rule: the CRM {@code name_search} through {@code customer_id},
   *       the walk-in {@code customer_name} folded in the query) or by phone digits (CRM {@code
   *       phone_e164}, walk-in {@code customer_phone} stripped to digits; Arabic-Indic folded) —
   *       and the claimant ({@code claimed_by_customer_id}) the same way, for a claim that named no
   *       order.
   * </ul>
   *
   * <p>Org-scoped like the orders search, so the planner never leaves the tenant's own rows; the
   * {@code EXISTS} legs are one index hit per candidate row.
   */
  private static Condition searchLegs(UUID orgId, String term) {
    Field<String> folded = DSL.field("fold_search({0})", String.class, DSL.val(term));
    Field<String> foldedPattern = DSL.concat(DSL.inline("%"), folded, DSL.inline("%"));
    String numeric = Text.normalizeNumeric(term);
    String digits = numeric == null ? "" : numeric.replaceAll("[^0-9]", "");
    String digitsPattern = digits.isEmpty() ? null : "%" + digits + "%";

    Condition byReference = PAYMENT_TRANSACTION.PROVIDER_REF.containsIgnoreCase(term);

    // The linked order: through the payment, or the claim.
    Condition orderMatches =
        SALES_ORDER
            .ORDER_NUMBER
            .containsIgnoreCase(term)
            .or(
                DSL.field("fold_search({0})", String.class, SALES_ORDER.CUSTOMER_NAME)
                    .like(foldedPattern))
            .or(customerMatches(SALES_ORDER.CUSTOMER_ID, foldedPattern, digitsPattern));
    if (digitsPattern != null) {
      orderMatches =
          orderMatches.or(
              DSL.field(
                      "regexp_replace({0}, '[^0-9]', '', 'g')",
                      String.class, SALES_ORDER.CUSTOMER_PHONE)
                  .like(digitsPattern));
    }
    Condition byLinkedOrder =
        DSL.exists(
            DSL.selectOne()
                .from(SALES_ORDER)
                .where(SALES_ORDER.ORG_ID.eq(orgId))
                .and(
                    SALES_ORDER
                        .ID
                        .eq(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID)
                        .or(
                            SALES_ORDER.ID.in(
                                DSL.select(PAYMENT.SALES_ORDER_ID)
                                    .from(PAYMENT)
                                    .where(
                                        PAYMENT.PAYMENT_TRANSACTION_ID.eq(
                                            PAYMENT_TRANSACTION.ID)))))
                .and(orderMatches));

    Condition byClaimant =
        customerMatches(PAYMENT_TRANSACTION.CLAIMED_BY_CUSTOMER_ID, foldedPattern, digitsPattern);

    return byReference.or(byLinkedOrder).or(byClaimant);
  }

  /**
   * EXISTS on the CRM customer behind {@code customerId}: folded name, or phone digits when any.
   */
  private static Condition customerMatches(
      Field<UUID> customerId, Field<String> foldedPattern, String digitsPattern) {
    Condition match = CUSTOMER.NAME_SEARCH.like(foldedPattern);
    if (digitsPattern != null) {
      match = match.or(CUSTOMER.PHONE_E164.like(digitsPattern));
    }
    return DSL.exists(DSL.selectOne().from(CUSTOMER).where(CUSTOMER.ID.eq(customerId)).and(match));
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
        r.getUpdatedAt(),
        r.getCashShiftId());
  }
}
