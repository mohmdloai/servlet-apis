package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.INVOICE_NUMBER_COUNTER;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE_LINE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.InvoiceListFilter;
import com.loai.inventory.domain.model.InvoiceListStats;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.repository.generated.tables.records.SalesInvoiceLineRecord;
import com.loai.inventory.repository.generated.tables.records.SalesInvoiceRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SalesInvoiceRepositoryImpl implements SalesInvoiceRepository {

  private static final Logger log = LoggerFactory.getLogger(SalesInvoiceRepositoryImpl.class);

  private final DSLContext dsl;

  public SalesInvoiceRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(SalesInvoice invoice, List<SalesInvoiceLine> lines) {
    dsl.insertInto(SALES_INVOICE)
        .set(SALES_INVOICE.ID, invoice.getId())
        .set(SALES_INVOICE.ORG_ID, invoice.getOrgId())
        .set(SALES_INVOICE.CUSTOMER_ID, invoice.getCustomerId())
        .set(SALES_INVOICE.SALES_ORDER_ID, invoice.getSalesOrderId())
        .set(SALES_INVOICE.FULFILLMENT_ID, invoice.getFulfillmentId())
        .set(SALES_INVOICE.INVOICE_NUMBER, invoice.getInvoiceNumber())
        .set(
            SALES_INVOICE.STATUS,
            com.loai.inventory.repository.generated.enums.InvoiceStatus.valueOf(
                invoice.getStatus().name()))
        .set(SALES_INVOICE.SUBTOTAL, invoice.getSubtotal())
        .set(SALES_INVOICE.TAX_TOTAL, invoice.getTaxTotal())
        .set(SALES_INVOICE.SHIPPING_TOTAL, invoice.getShippingTotal())
        .set(SALES_INVOICE.DISCOUNT_TOTAL, invoice.getDiscountTotal())
        .set(SALES_INVOICE.GRAND_TOTAL, invoice.getGrandTotal())
        .set(SALES_INVOICE.CURRENCY, invoice.getCurrency())
        .set(SALES_INVOICE.CUSTOMER_NAME, invoice.getCustomerName())
        .set(SALES_INVOICE.CUSTOMER_EMAIL, invoice.getCustomerEmail())
        .set(SALES_INVOICE.CUSTOMER_PHONE, invoice.getCustomerPhone())
        .set(SALES_INVOICE.CUSTOMER_ADDRESS, invoice.getCustomerAddress())
        .set(SALES_INVOICE.PAID_AMOUNT, invoice.getPaidAmount())
        .set(SALES_INVOICE.ISSUED_AT, invoice.getIssuedAt())
        .set(SALES_INVOICE.CREATED_AT, invoice.getCreatedAt())
        .set(SALES_INVOICE.UPDATED_AT, invoice.getUpdatedAt())
        .execute();

    if (!lines.isEmpty()) {
      List<SalesInvoiceLineRecord> records = new ArrayList<>(lines.size());
      for (SalesInvoiceLine line : lines) {
        SalesInvoiceLineRecord r = dsl.newRecord(SALES_INVOICE_LINE);
        r.setId(line.getId());
        r.setSalesInvoiceId(line.getSalesInvoiceId());
        r.setProductId(line.getProductId());
        r.setDescription(line.getDescription());
        r.setQuantity(line.getQuantity());
        r.setUnitPrice(line.getUnitPrice());
        r.setTaxRate(line.getTaxRate());
        r.setLineSubtotal(line.getLineSubtotal());
        r.setLineTax(line.getLineTax());
        r.setLineTotal(line.getLineTotal());
        records.add(r);
      }
      dsl.batchInsert(records).execute();
    }

    log.debug(
        "Inserted sales_invoice id={} orgId={} number={} status={} lines={}",
        invoice.getId(),
        invoice.getOrgId(),
        invoice.getInvoiceNumber(),
        invoice.getStatus(),
        lines.size());
  }

  @Override
  public Optional<SalesInvoice> findByFulfillmentId(UUID orgId, UUID fulfillmentId) {
    // Exclude VOID: after a void/reissue the live (corrected) invoice is the one re-delivery
    // idempotency must return — never a cancelled sibling. Mirrors the partial unique index.
    return dsl.selectFrom(SALES_INVOICE)
        .where(
            SALES_INVOICE
                .ORG_ID
                .eq(orgId)
                .and(SALES_INVOICE.FULFILLMENT_ID.eq(fulfillmentId))
                .and(
                    SALES_INVOICE.STATUS.ne(
                        com.loai.inventory.repository.generated.enums.InvoiceStatus.VOID)))
        .fetchOptional()
        .map(this::toInvoice);
  }

  @Override
  public Optional<SalesInvoice> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(SALES_INVOICE)
        .where(SALES_INVOICE.ORG_ID.eq(orgId).and(SALES_INVOICE.ID.eq(id)))
        .fetchOptional()
        .map(this::toInvoice);
  }

  @Override
  public Optional<SalesInvoice> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(SALES_INVOICE)
        .where(SALES_INVOICE.ORG_ID.eq(orgId).and(SALES_INVOICE.ID.eq(id)))
        .forUpdate()
        .fetchOptional()
        .map(this::toInvoice);
  }

  @Override
  public List<SalesInvoice> findByOrderId(UUID orgId, UUID salesOrderId) {
    return dsl.selectFrom(SALES_INVOICE)
        .where(SALES_INVOICE.ORG_ID.eq(orgId).and(SALES_INVOICE.SALES_ORDER_ID.eq(salesOrderId)))
        .orderBy(SALES_INVOICE.CREATED_AT.asc(), SALES_INVOICE.ID.asc())
        .fetch()
        .map(this::toInvoice);
  }

  @Override
  public List<SalesInvoice> list(UUID orgId, InvoiceListFilter filter, int offset, int limit) {
    var query = dsl.selectFrom(SALES_INVOICE).where(conditions(orgId, filter));
    // Queue vs ledger: a status filter is a worklist — oldest first (ISSUED = collect the money
    // first); no filter is the audit ledger — newest first. Mirrors RefundRepositoryImpl#list.
    // The other dimensions (q, dates, paid, amount) narrow the set and never touch the order.
    var ordered =
        filter.isQueue()
            ? query.orderBy(SALES_INVOICE.CREATED_AT.asc(), SALES_INVOICE.ID.asc())
            : query.orderBy(SALES_INVOICE.CREATED_AT.desc(), SALES_INVOICE.ID.desc());
    return ordered.offset(offset).limit(limit).fetch().map(this::toInvoice);
  }

  private static final com.loai.inventory.repository.generated.enums.InvoiceStatus DB_ISSUED =
      com.loai.inventory.repository.generated.enums.InvoiceStatus.ISSUED;
  private static final com.loai.inventory.repository.generated.enums.InvoiceStatus DB_PAID =
      com.loai.inventory.repository.generated.enums.InvoiceStatus.PAID;

  @Override
  public InvoiceListStats stats(UUID orgId, InvoiceListFilter filter) {
    // One pass over the filtered set: the pager's total and the two money figures. Sums are
    // conditional so the same rows feed all three — outstanding counts the ISSUED meter's
    // remainder; issued counts every document still in force (ISSUED + PAID, never VOID).
    Field<BigDecimal> zero = DSL.inline(BigDecimal.ZERO);
    Field<BigDecimal> outstanding =
        DSL.sum(
            DSL.when(
                    SALES_INVOICE.STATUS.eq(DB_ISSUED),
                    SALES_INVOICE.GRAND_TOTAL.minus(SALES_INVOICE.PAID_AMOUNT))
                .otherwise(zero));
    Field<BigDecimal> issued =
        DSL.sum(
            DSL.when(SALES_INVOICE.STATUS.in(DB_ISSUED, DB_PAID), SALES_INVOICE.GRAND_TOTAL)
                .otherwise(zero));
    var row =
        dsl.select(DSL.count(), outstanding, issued)
            .from(SALES_INVOICE)
            .where(conditions(orgId, filter))
            .fetchOne();
    if (row == null) {
      return InvoiceListStats.empty();
    }
    return new InvoiceListStats(row.value1().longValue(), money(row.value2()), money(row.value3()));
  }

  private static BigDecimal money(BigDecimal sum) {
    return (sum == null ? BigDecimal.ZERO : sum).setScale(2, java.math.RoundingMode.HALF_EVEN);
  }

  @Override
  public Map<InvoiceStatus, Long> countByStatus(UUID orgId) {
    // The same org predicate as the unfiltered ledger, partitioned by status — a tab's chip and
    // its list's total are the same rows by construction (mirrors SalesOrderRepositoryImpl).
    return dsl.select(SALES_INVOICE.STATUS, DSL.count())
        .from(SALES_INVOICE)
        .where(conditions(orgId, InvoiceListFilter.none()))
        .groupBy(SALES_INVOICE.STATUS)
        .fetchMap(r -> InvoiceStatus.valueOf(r.value1().name()), r -> r.value2().longValue());
  }

  @Override
  public List<SalesInvoice> findByCustomerId(UUID orgId, UUID customerId, int offset, int limit) {
    return dsl.selectFrom(SALES_INVOICE)
        .where(customerLiveConditions(orgId, customerId))
        .orderBy(SALES_INVOICE.CREATED_AT.desc(), SALES_INVOICE.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toInvoice);
  }

  @Override
  public long countByCustomerId(UUID orgId, UUID customerId) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_INVOICE).where(customerLiveConditions(orgId, customerId)));
  }

  /** {@code (org, customer)}-scoped, VOID excluded — the customer only ever sees live documents. */
  private static org.jooq.Condition customerLiveConditions(UUID orgId, UUID customerId) {
    return SALES_INVOICE
        .ORG_ID
        .eq(orgId)
        .and(SALES_INVOICE.CUSTOMER_ID.eq(customerId))
        .and(
            SALES_INVOICE.STATUS.ne(
                com.loai.inventory.repository.generated.enums.InvoiceStatus.VOID));
  }

  /**
   * The worklist predicate — one definition for the rows, their total and the money summary, so
   * none of the three can disagree ({@code stories/invoice_filters.md}). Every dimension of the
   * {@link InvoiceListFilter} ANDs onto the org scope:
   *
   * <ul>
   *   <li><b>status</b> — the tab, exactly as before.
   *   <li><b>q</b> — OR of: {@code invoice_number ILIKE '%q%'}; the order's number through an
   *       {@code EXISTS} on {@code sales_order_id}; the frozen {@code customer_name} folded on both
   *       sides with the DB's own {@code fold_search} (the snapshot has no generated twin, so it is
   *       folded in the query — org-scoped, fine) and the CRM row's generated {@code name_search}
   *       through {@code customer_id}; and, only when {@code q} carries digits, the frozen {@code
   *       customer_phone} stripped to digits and the CRM {@code phone_e164}. The same legs {@code
   *       SalesOrderRepositoryImpl.searchLegs} uses, so a merchant's one habit finds both
   *       documents.
   *   <li><b>issued window</b> — half-open on {@code issued_at}: {@code >= from}, {@code < to}.
   *   <li><b>paid</b> — {@code paid_amount = 0}, or {@code 0 < paid_amount < grand_total}.
   *   <li><b>amount</b> — inclusive bounds on {@code grand_total}.
   * </ul>
   */
  private static Condition conditions(UUID orgId, InvoiceListFilter f) {
    Condition c = SALES_INVOICE.ORG_ID.eq(orgId);
    if (f.status() != null) {
      c =
          c.and(
              SALES_INVOICE.STATUS.eq(
                  com.loai.inventory.repository.generated.enums.InvoiceStatus.valueOf(
                      f.status().name())));
    }
    if (f.hasQuery()) {
      c = c.and(searchLegs(f.q().trim()));
    }
    if (f.issuedFrom() != null) {
      c = c.and(SALES_INVOICE.ISSUED_AT.ge(f.issuedFrom()));
    }
    if (f.issuedTo() != null) {
      c = c.and(SALES_INVOICE.ISSUED_AT.lt(f.issuedTo()));
    }
    if (f.paid() != null) {
      c =
          switch (f.paid()) {
            case NONE -> c.and(SALES_INVOICE.PAID_AMOUNT.eq(BigDecimal.ZERO));
            case PARTIAL ->
                c.and(SALES_INVOICE.PAID_AMOUNT.gt(BigDecimal.ZERO))
                    .and(SALES_INVOICE.PAID_AMOUNT.lt(SALES_INVOICE.GRAND_TOTAL));
          };
    }
    if (f.minTotal() != null) {
      c = c.and(SALES_INVOICE.GRAND_TOTAL.ge(f.minTotal()));
    }
    if (f.maxTotal() != null) {
      c = c.and(SALES_INVOICE.GRAND_TOTAL.le(f.maxTotal()));
    }
    return c;
  }

  private static Condition searchLegs(String term) {
    Field<String> folded = DSL.field("fold_search({0})", String.class, DSL.val(term));
    Field<String> foldedPattern = DSL.concat(DSL.inline("%"), folded, DSL.inline("%"));
    Condition byNumber = SALES_INVOICE.INVOICE_NUMBER.containsIgnoreCase(term);
    Condition byOrderNumber =
        DSL.exists(
            DSL.selectOne()
                .from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(SALES_INVOICE.SALES_ORDER_ID))
                .and(SALES_ORDER.ORDER_NUMBER.containsIgnoreCase(term)));
    Condition bySnapshotName =
        DSL.field("fold_search({0})", String.class, SALES_INVOICE.CUSTOMER_NAME)
            .like(foldedPattern);
    Condition byCrmName =
        DSL.exists(
            DSL.selectOne()
                .from(CUSTOMER)
                .where(CUSTOMER.ID.eq(SALES_INVOICE.CUSTOMER_ID))
                .and(CUSTOMER.NAME_SEARCH.like(foldedPattern)));
    Condition legs = byNumber.or(byOrderNumber).or(bySnapshotName).or(byCrmName);

    String numeric = Text.normalizeNumeric(term);
    String digits = numeric == null ? "" : numeric.replaceAll("[^0-9]", "");
    if (!digits.isEmpty()) {
      String digitsPattern = "%" + digits + "%";
      Condition bySnapshotPhone =
          DSL.field(
                  "regexp_replace({0}, '[^0-9]', '', 'g')",
                  String.class, SALES_INVOICE.CUSTOMER_PHONE)
              .like(digitsPattern);
      Condition byCrmPhone =
          DSL.exists(
              DSL.selectOne()
                  .from(CUSTOMER)
                  .where(CUSTOMER.ID.eq(SALES_INVOICE.CUSTOMER_ID))
                  .and(CUSTOMER.PHONE_E164.like(digitsPattern)));
      legs = legs.or(bySnapshotPhone).or(byCrmPhone);
    }
    return legs;
  }

  @Override
  public void updatePaymentState(SalesInvoice invoice) {
    dsl.update(SALES_INVOICE)
        .set(
            SALES_INVOICE.STATUS,
            com.loai.inventory.repository.generated.enums.InvoiceStatus.valueOf(
                invoice.getStatus().name()))
        .set(SALES_INVOICE.PAID_AMOUNT, invoice.getPaidAmount())
        .set(SALES_INVOICE.UPDATED_AT, invoice.getUpdatedAt())
        .where(
            SALES_INVOICE.ID.eq(invoice.getId()).and(SALES_INVOICE.ORG_ID.eq(invoice.getOrgId())))
        .execute();
  }

  @Override
  public void updateVoidState(SalesInvoice invoice) {
    dsl.update(SALES_INVOICE)
        .set(
            SALES_INVOICE.STATUS,
            com.loai.inventory.repository.generated.enums.InvoiceStatus.valueOf(
                invoice.getStatus().name()))
        .set(SALES_INVOICE.VOIDED_AT, invoice.getVoidedAt())
        .set(SALES_INVOICE.VOID_REASON, invoice.getVoidReason())
        .set(SALES_INVOICE.UPDATED_AT, invoice.getUpdatedAt())
        .where(
            SALES_INVOICE.ID.eq(invoice.getId()).and(SALES_INVOICE.ORG_ID.eq(invoice.getOrgId())))
        .execute();
  }

  @Override
  public List<SalesInvoiceLine> findLinesByInvoiceId(UUID salesInvoiceId) {
    return dsl.selectFrom(SALES_INVOICE_LINE)
        .where(SALES_INVOICE_LINE.SALES_INVOICE_ID.eq(salesInvoiceId))
        .orderBy(SALES_INVOICE_LINE.ID.asc())
        .fetch()
        .map(this::toLine);
  }

  @Override
  public String claimInvoiceNumber(UUID orgId, int year) {
    // Ensure the counter row exists without disturbing a concurrent claimant's value.
    dsl.insertInto(INVOICE_NUMBER_COUNTER)
        .columns(
            INVOICE_NUMBER_COUNTER.ORG_ID,
            INVOICE_NUMBER_COUNTER.YEAR,
            INVOICE_NUMBER_COUNTER.NEXT_VAL)
        .values(orgId, year, 1L)
        .onConflict(INVOICE_NUMBER_COUNTER.ORG_ID, INVOICE_NUMBER_COUNTER.YEAR)
        .doNothing()
        .execute();

    // Lock the row for the rest of the transaction: gapless, since the increment rolls back with
    // the issuing transaction if it aborts.
    Long claimed =
        dsl.select(INVOICE_NUMBER_COUNTER.NEXT_VAL)
            .from(INVOICE_NUMBER_COUNTER)
            .where(
                INVOICE_NUMBER_COUNTER.ORG_ID.eq(orgId).and(INVOICE_NUMBER_COUNTER.YEAR.eq(year)))
            .forUpdate()
            .fetchOne(INVOICE_NUMBER_COUNTER.NEXT_VAL);
    if (claimed == null) {
      throw new IllegalStateException("claimInvoiceNumber found no counter row");
    }

    dsl.update(INVOICE_NUMBER_COUNTER)
        .set(INVOICE_NUMBER_COUNTER.NEXT_VAL, INVOICE_NUMBER_COUNTER.NEXT_VAL.plus(1))
        .where(INVOICE_NUMBER_COUNTER.ORG_ID.eq(orgId).and(INVOICE_NUMBER_COUNTER.YEAR.eq(year)))
        .execute();

    // Format here, not in the caller: the allocator is the sole owner of both the sequence and the
    // INV-YYYY-NNNN shape (the repair routine parses the trailing NNNN, so the two must agree).
    return String.format("INV-%d-%04d", year, claimed);
  }

  private SalesInvoice toInvoice(SalesInvoiceRecord r) {
    return SalesInvoice.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getCustomerId(),
        r.getSalesOrderId(),
        r.getFulfillmentId(),
        r.getInvoiceNumber(),
        InvoiceStatus.valueOf(r.getStatus().name()),
        r.getSubtotal(),
        r.getTaxTotal(),
        r.getShippingTotal(),
        r.getDiscountTotal(),
        r.getGrandTotal(),
        r.getCurrency(),
        r.getCustomerName(),
        r.getCustomerEmail(),
        r.getCustomerPhone(),
        r.getCustomerAddress(),
        r.getPaidAmount(),
        r.getIssuedAt(),
        r.getVoidedAt(),
        r.getVoidReason(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }

  private SalesInvoiceLine toLine(SalesInvoiceLineRecord r) {
    return SalesInvoiceLine.rehydrate(
        r.getId(),
        r.getSalesInvoiceId(),
        r.getProductId(),
        r.getDescription(),
        r.getQuantity(),
        r.getUnitPrice(),
        r.getTaxRate(),
        r.getLineSubtotal(),
        r.getLineTax(),
        r.getLineTotal());
  }
}
