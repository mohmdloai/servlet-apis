package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CASH_MOVEMENT;
import static com.loai.inventory.repository.generated.Tables.CASH_SHIFT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.domain.model.CashShift;
import com.loai.inventory.domain.repository.CashShiftRepository;
import com.loai.inventory.repository.generated.enums.CashMovementKind;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentProvider;
import com.loai.inventory.repository.generated.tables.records.CashShiftRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public final class CashShiftRepositoryImpl implements CashShiftRepository {

  /** The change hand-back is a refund with no note and this marker (RefundService). */
  private static final String COUNTER_CHANGE_NOTE = "counter change";

  private final DSLContext dsl;

  public CashShiftRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(CashShift s) {
    dsl.insertInto(CASH_SHIFT)
        .set(CASH_SHIFT.ID, s.getId())
        .set(CASH_SHIFT.ORG_ID, s.getOrgId())
        .set(CASH_SHIFT.OPENED_BY, s.getOpenedBy())
        .set(CASH_SHIFT.OPENED_AT, s.getOpenedAt())
        .set(CASH_SHIFT.AUTO_OPENED, s.isAutoOpened())
        .set(CASH_SHIFT.STARTING_CASH, s.getStartingCash())
        .set(CASH_SHIFT.NOTE, s.getNote())
        .set(CASH_SHIFT.CREATED_AT, s.getCreatedAt())
        .set(CASH_SHIFT.UPDATED_AT, s.getUpdatedAt())
        .execute();
  }

  @Override
  public void update(CashShift s) {
    dsl.update(CASH_SHIFT)
        .set(CASH_SHIFT.STARTING_CASH, s.getStartingCash())
        .set(CASH_SHIFT.CLOSED_BY, s.getClosedBy())
        .set(CASH_SHIFT.CLOSED_AT, s.getClosedAt())
        .set(CASH_SHIFT.COUNTED_CASH, s.getCountedCash())
        .set(CASH_SHIFT.EXPECTED_CASH, s.getExpectedCash())
        .set(CASH_SHIFT.NOTE, s.getNote())
        .set(CASH_SHIFT.UPDATED_AT, s.getUpdatedAt())
        .where(CASH_SHIFT.ID.eq(s.getId()).and(CASH_SHIFT.ORG_ID.eq(s.getOrgId())))
        .execute();
  }

  @Override
  public Optional<CashShift> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(CASH_SHIFT)
        .where(CASH_SHIFT.ID.eq(id).and(CASH_SHIFT.ORG_ID.eq(orgId)))
        .fetchOptional(CashShiftRepositoryImpl::toShift);
  }

  @Override
  public Optional<CashShift> findOpen(UUID orgId) {
    return dsl.selectFrom(CASH_SHIFT)
        .where(CASH_SHIFT.ORG_ID.eq(orgId).and(CASH_SHIFT.CLOSED_AT.isNull()))
        .fetchOptional(CashShiftRepositoryImpl::toShift);
  }

  @Override
  public Optional<CashShift> findOpenForUpdate(UUID orgId) {
    return dsl.selectFrom(CASH_SHIFT)
        .where(CASH_SHIFT.ORG_ID.eq(orgId).and(CASH_SHIFT.CLOSED_AT.isNull()))
        .forUpdate()
        .fetchOptional(CashShiftRepositoryImpl::toShift);
  }

  @Override
  public Optional<CashShift> findLastClosed(UUID orgId) {
    return dsl.selectFrom(CASH_SHIFT)
        .where(CASH_SHIFT.ORG_ID.eq(orgId).and(CASH_SHIFT.CLOSED_AT.isNotNull()))
        .orderBy(CASH_SHIFT.CLOSED_AT.desc(), CASH_SHIFT.ID.desc())
        .limit(1)
        .fetchOptional(CashShiftRepositoryImpl::toShift);
  }

  @Override
  public List<CashShift> list(UUID orgId, int offset, int limit) {
    return dsl.selectFrom(CASH_SHIFT)
        .where(CASH_SHIFT.ORG_ID.eq(orgId))
        .orderBy(CASH_SHIFT.OPENED_AT.desc(), CASH_SHIFT.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch(CashShiftRepositoryImpl::toShift);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(CASH_SHIFT, CASH_SHIFT.ORG_ID.eq(orgId));
  }

  /**
   * Four small reads, one call: the tenders by provider, the DEBITs split into change and refunds
   * through the refund row each references, the receipts and their discounts through the payment
   * rows, and the movements by kind. NUMERIC stays NUMERIC throughout.
   */
  @Override
  public Totals totals(UUID orgId, UUID shiftId) {
    var stamped =
        PAYMENT_TRANSACTION.ORG_ID.eq(orgId).and(PAYMENT_TRANSACTION.CASH_SHIFT_ID.eq(shiftId));
    var cashCredit =
        stamped
            .and(PAYMENT_TRANSACTION.PROVIDER.eq(PaymentProvider.cash))
            .and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.CREDIT));
    var instapayCredit =
        stamped
            .and(PAYMENT_TRANSACTION.PROVIDER.eq(PaymentProvider.instapay_in_store))
            .and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.CREDIT));
    BigDecimal cashSales = sum(PAYMENT_TRANSACTION.AMOUNT, cashCredit);
    BigDecimal instapay = sum(PAYMENT_TRANSACTION.AMOUNT, instapayCredit);

    // Cash DEBITs: the change hand-back is the refund whose note is the counter-change marker
    // and that credits no note; everything else cash is a refund of goods.
    Record2<BigDecimal, BigDecimal> debits =
        dsl.select(
                DSL.coalesce(
                    DSL.sum(
                        DSL.when(
                                REFUND
                                    .CREDIT_NOTE_ID
                                    .isNull()
                                    .and(REFUND.NOTES.eq(COUNTER_CHANGE_NOTE)),
                                PAYMENT_TRANSACTION.AMOUNT)
                            .otherwise(BigDecimal.ZERO)),
                    BigDecimal.ZERO),
                DSL.coalesce(
                    DSL.sum(
                        DSL.when(
                                REFUND
                                    .CREDIT_NOTE_ID
                                    .isNull()
                                    .and(REFUND.NOTES.eq(COUNTER_CHANGE_NOTE)),
                                BigDecimal.ZERO)
                            .otherwise(PAYMENT_TRANSACTION.AMOUNT)),
                    BigDecimal.ZERO))
            .from(PAYMENT_TRANSACTION)
            .leftJoin(REFUND)
            .on(REFUND.PAYMENT_TRANSACTION_ID.eq(PAYMENT_TRANSACTION.ID))
            .where(
                stamped
                    .and(PAYMENT_TRANSACTION.PROVIDER.eq(PaymentProvider.cash))
                    .and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.DEBIT)))
            .fetchOne();
    BigDecimal changeGiven = debits == null ? BigDecimal.ZERO : debits.value1();
    BigDecimal cashRefunds = debits == null ? BigDecimal.ZERO : debits.value2();

    // Receipts: the distinct orders behind the stamped tenders; discounts: their discount_total.
    var orders =
        dsl.selectDistinct(PAYMENT.SALES_ORDER_ID.as("order_id"))
            .from(PAYMENT)
            .join(PAYMENT_TRANSACTION)
            .on(PAYMENT.PAYMENT_TRANSACTION_ID.eq(PAYMENT_TRANSACTION.ID))
            .where(stamped.and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.CREDIT)))
            .asTable("shift_orders");
    var orderId = orders.field("order_id", UUID.class);
    Record2<Integer, BigDecimal> receipts =
        dsl.select(DSL.count(), DSL.coalesce(DSL.sum(SALES_ORDER.DISCOUNT_TOTAL), BigDecimal.ZERO))
            .from(orders)
            .join(SALES_ORDER)
            .on(SALES_ORDER.ID.eq(orderId))
            .fetchOne();
    long receiptCount = receipts == null ? 0L : receipts.value1();
    BigDecimal discounts = receipts == null ? BigDecimal.ZERO : receipts.value2();

    BigDecimal payIn = movementSum(orgId, shiftId, CashMovementKind.PAY_IN);
    BigDecimal payOut = movementSum(orgId, shiftId, CashMovementKind.PAY_OUT);

    return new Totals(
        cashSales, changeGiven, cashRefunds, instapay, payIn, payOut, receiptCount, discounts);
  }

  private BigDecimal sum(
      org.jooq.TableField<
              com.loai.inventory.repository.generated.tables.records.PaymentTransactionRecord,
              BigDecimal>
          field,
      org.jooq.Condition where) {
    Record1<BigDecimal> r =
        dsl.select(DSL.coalesce(DSL.sum(field), BigDecimal.ZERO))
            .from(PAYMENT_TRANSACTION)
            .where(where)
            .fetchOne();
    return r == null ? BigDecimal.ZERO : r.value1();
  }

  private BigDecimal movementSum(UUID orgId, UUID shiftId, CashMovementKind kind) {
    Record1<BigDecimal> r =
        dsl.select(DSL.coalesce(DSL.sum(CASH_MOVEMENT.AMOUNT), BigDecimal.ZERO))
            .from(CASH_MOVEMENT)
            .where(
                CASH_MOVEMENT
                    .ORG_ID
                    .eq(orgId)
                    .and(CASH_MOVEMENT.SHIFT_ID.eq(shiftId))
                    .and(CASH_MOVEMENT.KIND.eq(kind)))
            .fetchOne();
    return r == null ? BigDecimal.ZERO : r.value1();
  }

  private static CashShift toShift(CashShiftRecord r) {
    return CashShift.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getOpenedBy(),
        r.getOpenedAt(),
        Boolean.TRUE.equals(r.getAutoOpened()),
        r.getStartingCash(),
        r.getClosedBy(),
        r.getClosedAt(),
        r.getCountedCash(),
        r.getExpectedCash(),
        r.getNote(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
