package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE;
import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE_LINE;
import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE_NUMBER_COUNTER;

import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import com.loai.inventory.domain.model.CreditNoteReason;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.repository.CreditNoteRepository;
import com.loai.inventory.repository.generated.tables.records.CreditNoteLineRecord;
import com.loai.inventory.repository.generated.tables.records.CreditNoteRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CreditNoteRepositoryImpl implements CreditNoteRepository {

  private static final Logger log = LoggerFactory.getLogger(CreditNoteRepositoryImpl.class);

  private final DSLContext dsl;

  public CreditNoteRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(CreditNote note, List<CreditNoteLine> lines) {
    dsl.insertInto(CREDIT_NOTE)
        .set(CREDIT_NOTE.ID, note.getId())
        .set(CREDIT_NOTE.ORG_ID, note.getOrgId())
        .set(CREDIT_NOTE.CUSTOMER_ID, note.getCustomerId())
        .set(CREDIT_NOTE.SALES_INVOICE_ID, note.getSalesInvoiceId())
        .set(
            CREDIT_NOTE.REASON,
            com.loai.inventory.repository.generated.enums.CreditNoteReason.valueOf(
                note.getReason().name()))
        .set(CREDIT_NOTE.REASON_NOTE, note.getReasonNote())
        .set(CREDIT_NOTE.SUBTOTAL, note.getSubtotal())
        .set(CREDIT_NOTE.TAX_TOTAL, note.getTaxTotal())
        .set(CREDIT_NOTE.TOTAL, note.getTotal())
        .set(CREDIT_NOTE.CURRENCY, note.getCurrency())
        .set(CREDIT_NOTE.CREDIT_NOTE_NUMBER, note.getCreditNoteNumber())
        .set(
            CREDIT_NOTE.STATUS,
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.valueOf(
                note.getStatus().name()))
        .set(CREDIT_NOTE.ISSUED_AT, note.getIssuedAt())
        .set(CREDIT_NOTE.CREATED_AT, note.getCreatedAt())
        .set(CREDIT_NOTE.UPDATED_AT, note.getUpdatedAt())
        .execute();

    if (!lines.isEmpty()) {
      List<CreditNoteLineRecord> records = new ArrayList<>(lines.size());
      for (CreditNoteLine line : lines) {
        CreditNoteLineRecord r = dsl.newRecord(CREDIT_NOTE_LINE);
        r.setId(line.getId());
        r.setCreditNoteId(line.getCreditNoteId());
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
        "Inserted credit_note id={} orgId={} number={} status={} lines={}",
        note.getId(),
        note.getOrgId(),
        note.getCreditNoteNumber(),
        note.getStatus(),
        lines.size());
  }

  @Override
  public Optional<CreditNote> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(CREDIT_NOTE)
        .where(CREDIT_NOTE.ORG_ID.eq(orgId).and(CREDIT_NOTE.ID.eq(id)))
        .fetchOptional()
        .map(this::toCreditNote);
  }

  @Override
  public Optional<CreditNote> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(CREDIT_NOTE)
        .where(CREDIT_NOTE.ORG_ID.eq(orgId).and(CREDIT_NOTE.ID.eq(id)))
        .forUpdate()
        .fetchOptional()
        .map(this::toCreditNote);
  }

  @Override
  public List<CreditNoteLine> findLinesByCreditNoteId(UUID creditNoteId) {
    return dsl.selectFrom(CREDIT_NOTE_LINE)
        .where(CREDIT_NOTE_LINE.CREDIT_NOTE_ID.eq(creditNoteId))
        .fetch()
        .map(this::toLine);
  }

  @Override
  public List<CreditNote> findByInvoiceId(
      UUID orgId, UUID salesInvoiceId, CreditNoteStatus status) {
    org.jooq.Condition c =
        CREDIT_NOTE.ORG_ID.eq(orgId).and(CREDIT_NOTE.SALES_INVOICE_ID.eq(salesInvoiceId));
    if (status != null) {
      c =
          c.and(
              CREDIT_NOTE.STATUS.eq(
                  com.loai.inventory.repository.generated.enums.CreditNoteStatus.valueOf(
                      status.name())));
    }
    return dsl.selectFrom(CREDIT_NOTE)
        .where(c)
        .orderBy(CREDIT_NOTE.CREATED_AT.asc(), CREDIT_NOTE.ID.asc())
        .fetch()
        .map(this::toCreditNote);
  }

  @Override
  public Map<UUID, CreditNoteRef> findRefsByIds(UUID orgId, Collection<UUID> creditNoteIds) {
    if (creditNoteIds.isEmpty()) {
      return Map.of();
    }
    return dsl.select(CREDIT_NOTE.ID, CREDIT_NOTE.SALES_INVOICE_ID, CREDIT_NOTE.CREDIT_NOTE_NUMBER)
        .from(CREDIT_NOTE)
        .where(CREDIT_NOTE.ORG_ID.eq(orgId).and(CREDIT_NOTE.ID.in(creditNoteIds)))
        .fetchMap(
            CREDIT_NOTE.ID,
            r ->
                new CreditNoteRef(
                    r.get(CREDIT_NOTE.SALES_INVOICE_ID), r.get(CREDIT_NOTE.CREDIT_NOTE_NUMBER)));
  }

  @Override
  public void updateStatus(CreditNote note) {
    dsl.update(CREDIT_NOTE)
        .set(
            CREDIT_NOTE.STATUS,
            com.loai.inventory.repository.generated.enums.CreditNoteStatus.valueOf(
                note.getStatus().name()))
        .set(CREDIT_NOTE.UPDATED_AT, note.getUpdatedAt())
        .where(CREDIT_NOTE.ID.eq(note.getId()).and(CREDIT_NOTE.ORG_ID.eq(note.getOrgId())))
        .execute();
  }

  @Override
  public java.math.BigDecimal sumIssuedTotalByInvoice(UUID orgId, UUID salesInvoiceId) {
    return dsl.select(
            org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(CREDIT_NOTE.TOTAL), BigDecimal.ZERO))
        .from(CREDIT_NOTE)
        .where(
            CREDIT_NOTE
                .ORG_ID
                .eq(orgId)
                .and(CREDIT_NOTE.SALES_INVOICE_ID.eq(salesInvoiceId))
                .and(
                    CREDIT_NOTE.STATUS.in(
                        com.loai.inventory.repository.generated.enums.CreditNoteStatus.ISSUED,
                        com.loai.inventory.repository.generated.enums.CreditNoteStatus.SETTLED)))
        .fetchOne(0, BigDecimal.class);
  }

  @Override
  public long claimCreditNoteNumber(UUID orgId, int year) {
    dsl.insertInto(CREDIT_NOTE_NUMBER_COUNTER)
        .columns(
            CREDIT_NOTE_NUMBER_COUNTER.ORG_ID,
            CREDIT_NOTE_NUMBER_COUNTER.YEAR,
            CREDIT_NOTE_NUMBER_COUNTER.NEXT_VAL)
        .values(orgId, year, 1L)
        .onConflict(CREDIT_NOTE_NUMBER_COUNTER.ORG_ID, CREDIT_NOTE_NUMBER_COUNTER.YEAR)
        .doNothing()
        .execute();

    Long claimed =
        dsl.select(CREDIT_NOTE_NUMBER_COUNTER.NEXT_VAL)
            .from(CREDIT_NOTE_NUMBER_COUNTER)
            .where(
                CREDIT_NOTE_NUMBER_COUNTER
                    .ORG_ID
                    .eq(orgId)
                    .and(CREDIT_NOTE_NUMBER_COUNTER.YEAR.eq(year)))
            .forUpdate()
            .fetchOne(CREDIT_NOTE_NUMBER_COUNTER.NEXT_VAL);
    if (claimed == null) {
      throw new IllegalStateException("claimCreditNoteNumber found no counter row");
    }

    dsl.update(CREDIT_NOTE_NUMBER_COUNTER)
        .set(CREDIT_NOTE_NUMBER_COUNTER.NEXT_VAL, CREDIT_NOTE_NUMBER_COUNTER.NEXT_VAL.plus(1))
        .where(
            CREDIT_NOTE_NUMBER_COUNTER
                .ORG_ID
                .eq(orgId)
                .and(CREDIT_NOTE_NUMBER_COUNTER.YEAR.eq(year)))
        .execute();
    return claimed;
  }

  private CreditNote toCreditNote(CreditNoteRecord r) {
    return CreditNote.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getCustomerId(),
        r.getSalesInvoiceId(),
        CreditNoteReason.valueOf(r.getReason().name()),
        r.getReasonNote(),
        r.getSubtotal(),
        r.getTaxTotal(),
        r.getTotal(),
        r.getCurrency(),
        r.getCreditNoteNumber(),
        CreditNoteStatus.valueOf(r.getStatus().name()),
        r.getIssuedAt(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }

  private CreditNoteLine toLine(CreditNoteLineRecord r) {
    return CreditNoteLine.rehydrate(
        r.getId(),
        r.getCreditNoteId(),
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
