package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVOICE_NUMBER_COUNTER;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE_LINE;

import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.repository.generated.tables.records.SalesInvoiceLineRecord;
import com.loai.inventory.repository.generated.tables.records.SalesInvoiceRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
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
    return dsl.selectFrom(SALES_INVOICE)
        .where(SALES_INVOICE.ORG_ID.eq(orgId).and(SALES_INVOICE.FULFILLMENT_ID.eq(fulfillmentId)))
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
        .fetch()
        .map(this::toInvoice);
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
  public long claimInvoiceNumber(UUID orgId, int year) {
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
    return claimed;
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
}
