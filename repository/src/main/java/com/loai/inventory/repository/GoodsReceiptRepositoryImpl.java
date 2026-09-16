package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.GOODS_RECEIPT;
import static com.loai.inventory.repository.generated.Tables.GOODS_RECEIPT_LINE;
import static com.loai.inventory.repository.generated.Tables.GOODS_RECEIPT_NUMBER_COUNTER;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.GoodsReceipt;
import com.loai.inventory.domain.model.GoodsReceiptLine;
import com.loai.inventory.domain.model.GoodsReceiptListFilter;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.repository.GoodsReceiptRepository;
import com.loai.inventory.repository.generated.tables.records.GoodsReceiptLineRecord;
import com.loai.inventory.repository.generated.tables.records.GoodsReceiptRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class GoodsReceiptRepositoryImpl implements GoodsReceiptRepository {
  private final DSLContext dsl;

  public GoodsReceiptRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<GoodsReceipt> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(GOODS_RECEIPT)
        .where(GOODS_RECEIPT.ORG_ID.eq(orgId).and(GOODS_RECEIPT.ID.eq(id)))
        .fetchOptional()
        .map(GoodsReceiptRepositoryImpl::toReceipt);
  }

  @Override
  public Optional<GoodsReceipt> lockById(UUID orgId, UUID id) {
    return dsl.selectFrom(GOODS_RECEIPT)
        .where(GOODS_RECEIPT.ORG_ID.eq(orgId).and(GOODS_RECEIPT.ID.eq(id)))
        .forUpdate()
        .fetchOptional()
        .map(GoodsReceiptRepositoryImpl::toReceipt);
  }

  @Override
  public Optional<GoodsReceipt> findByIdempotencyKey(UUID orgId, String idempotencyKey) {
    return dsl.selectFrom(GOODS_RECEIPT)
        .where(GOODS_RECEIPT.ORG_ID.eq(orgId).and(GOODS_RECEIPT.IDEMPOTENCY_KEY.eq(idempotencyKey)))
        .fetchOptional()
        .map(GoodsReceiptRepositoryImpl::toReceipt);
  }

  @Override
  public List<GoodsReceiptLine> findLines(UUID goodsReceiptId) {
    return dsl.selectFrom(GOODS_RECEIPT_LINE)
        .where(GOODS_RECEIPT_LINE.GOODS_RECEIPT_ID.eq(goodsReceiptId))
        .orderBy(GOODS_RECEIPT_LINE.ID.asc())
        .fetch()
        .map(GoodsReceiptRepositoryImpl::toLine);
  }

  @Override
  public List<GoodsReceipt> list(UUID orgId, GoodsReceiptListFilter filter, int offset, int limit) {
    return dsl.selectFrom(GOODS_RECEIPT)
        .where(conditions(orgId, filter))
        // received_at DESC always: neither status is a worklist (see the interface Javadoc).
        .orderBy(GOODS_RECEIPT.RECEIVED_AT.desc(), GOODS_RECEIPT.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(GoodsReceiptRepositoryImpl::toReceipt);
  }

  @Override
  public long count(UUID orgId, GoodsReceiptListFilter filter) {
    return dsl.fetchCount(dsl.selectFrom(GOODS_RECEIPT).where(conditions(orgId, filter)));
  }

  /** org ∧ [supplier] ∧ [status] ∧ [from ≤ received_at < to] ∧ [q ~ our number ∨ theirs]. */
  private Condition conditions(UUID orgId, GoodsReceiptListFilter f) {
    Condition c = GOODS_RECEIPT.ORG_ID.eq(orgId);
    if (f == null) {
      return c;
    }
    if (f.supplierId() != null) {
      c = c.and(GOODS_RECEIPT.SUPPLIER_ID.eq(f.supplierId()));
    }
    if (f.status() != null) {
      c = c.and(GOODS_RECEIPT.STATUS.eq(f.status().name()));
    }
    if (f.from() != null) {
      c = c.and(GOODS_RECEIPT.RECEIVED_AT.ge(f.from()));
    }
    if (f.to() != null) {
      c = c.and(GOODS_RECEIPT.RECEIVED_AT.lt(f.to()));
    }
    if (f.q() != null && !f.q().isBlank()) {
      String term = f.q().trim();
      c =
          c.and(
              GOODS_RECEIPT
                  .RECEIPT_NUMBER
                  .containsIgnoreCase(term)
                  .or(GOODS_RECEIPT.SUPPLIER_REFERENCE.containsIgnoreCase(term)));
    }
    return c;
  }

  @Override
  public Map<UUID, Integer> lineCounts(Collection<UUID> receiptIds) {
    if (receiptIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Integer> out = new HashMap<>();
    dsl.select(GOODS_RECEIPT_LINE.GOODS_RECEIPT_ID, DSL.count())
        .from(GOODS_RECEIPT_LINE)
        .where(GOODS_RECEIPT_LINE.GOODS_RECEIPT_ID.in(receiptIds))
        .groupBy(GOODS_RECEIPT_LINE.GOODS_RECEIPT_ID)
        .fetch()
        .forEach(r -> out.put(r.value1(), r.value2()));
    return out;
  }

  @Override
  public Map<UUID, String> findReceiptNumbersByIds(UUID orgId, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> out = new HashMap<>();
    dsl.select(GOODS_RECEIPT.ID, GOODS_RECEIPT.RECEIPT_NUMBER)
        .from(GOODS_RECEIPT)
        .where(GOODS_RECEIPT.ORG_ID.eq(orgId).and(GOODS_RECEIPT.ID.in(ids)))
        .fetch()
        .forEach(r -> out.put(r.value1(), r.value2()));
    return out;
  }

  @Override
  public String claimReceiptNumber(UUID orgId, int year) {
    // V30's rule: ensure the row, lock it for the rest of the caller's transaction, bump. Gapless,
    // since a rollback un-burns the increment with everything else.
    dsl.insertInto(GOODS_RECEIPT_NUMBER_COUNTER)
        .columns(
            GOODS_RECEIPT_NUMBER_COUNTER.ORG_ID,
            GOODS_RECEIPT_NUMBER_COUNTER.YEAR,
            GOODS_RECEIPT_NUMBER_COUNTER.NEXT_VAL)
        .values(orgId, year, 1L)
        .onConflict(GOODS_RECEIPT_NUMBER_COUNTER.ORG_ID, GOODS_RECEIPT_NUMBER_COUNTER.YEAR)
        .doNothing()
        .execute();

    Long claimed =
        dsl.select(GOODS_RECEIPT_NUMBER_COUNTER.NEXT_VAL)
            .from(GOODS_RECEIPT_NUMBER_COUNTER)
            .where(
                GOODS_RECEIPT_NUMBER_COUNTER
                    .ORG_ID
                    .eq(orgId)
                    .and(GOODS_RECEIPT_NUMBER_COUNTER.YEAR.eq(year)))
            .forUpdate()
            .fetchOne(GOODS_RECEIPT_NUMBER_COUNTER.NEXT_VAL);
    if (claimed == null) {
      throw new IllegalStateException("claimReceiptNumber found no counter row");
    }

    dsl.update(GOODS_RECEIPT_NUMBER_COUNTER)
        .set(GOODS_RECEIPT_NUMBER_COUNTER.NEXT_VAL, GOODS_RECEIPT_NUMBER_COUNTER.NEXT_VAL.plus(1))
        .where(
            GOODS_RECEIPT_NUMBER_COUNTER
                .ORG_ID
                .eq(orgId)
                .and(GOODS_RECEIPT_NUMBER_COUNTER.YEAR.eq(year)))
        .execute();

    // Formatted here, like the invoice allocator: one owner for both the sequence and the shape.
    return String.format("GRN-%d-%05d", year, claimed);
  }

  @Override
  public GoodsReceipt insert(GoodsReceipt receipt) {
    GoodsReceiptRecord header =
        dsl.insertInto(GOODS_RECEIPT)
            .set(GOODS_RECEIPT.ORG_ID, receipt.getOrgId())
            .set(GOODS_RECEIPT.SUPPLIER_ID, receipt.getSupplierId())
            .set(GOODS_RECEIPT.RECEIPT_NUMBER, receipt.getReceiptNumber())
            .set(GOODS_RECEIPT.STATUS, receipt.getStatus().name())
            .set(GOODS_RECEIPT.RECEIVED_AT, receipt.getReceivedAt())
            .set(GOODS_RECEIPT.SUPPLIER_REFERENCE, receipt.getSupplierReference())
            .set(GOODS_RECEIPT.NOTES, receipt.getNotes())
            .set(GOODS_RECEIPT.TOTAL_COST, receipt.getTotalCost())
            .set(GOODS_RECEIPT.IDEMPOTENCY_KEY, receipt.getIdempotencyKey())
            .set(GOODS_RECEIPT.CREATED_BY, receipt.getCreatedBy())
            .returning()
            .fetchOne();
    if (header == null) {
      throw new IllegalStateException("INSERT into goods_receipt returned no record");
    }

    GoodsReceipt saved = toReceipt(header);
    var step =
        dsl.insertInto(
            GOODS_RECEIPT_LINE,
            GOODS_RECEIPT_LINE.ORG_ID,
            GOODS_RECEIPT_LINE.GOODS_RECEIPT_ID,
            GOODS_RECEIPT_LINE.PRODUCT_ID,
            GOODS_RECEIPT_LINE.QUANTITY,
            GOODS_RECEIPT_LINE.UNIT_COST,
            GOODS_RECEIPT_LINE.LINE_TOTAL);
    for (GoodsReceiptLine l : receipt.getLines()) {
      step =
          step.values(
              saved.getOrgId(),
              saved.getId(),
              l.getProductId(),
              l.getQuantity(),
              l.getUnitCost(),
              l.getLineTotal());
    }
    step.execute();
    saved.setLines(findLines(saved.getId()));
    return saved;
  }

  @Override
  public GoodsReceipt markVoided(
      UUID orgId, UUID id, String reason, UUID voidedBy, OffsetDateTime voidedAt) {
    GoodsReceiptRecord record =
        dsl.update(GOODS_RECEIPT)
            .set(GOODS_RECEIPT.STATUS, GoodsReceiptStatus.VOIDED.name())
            .set(GOODS_RECEIPT.VOIDED_AT, voidedAt)
            .set(GOODS_RECEIPT.VOID_REASON, reason)
            .set(GOODS_RECEIPT.VOIDED_BY, voidedBy)
            .set(GOODS_RECEIPT.UPDATED_AT, voidedAt)
            .where(GOODS_RECEIPT.ORG_ID.eq(orgId).and(GOODS_RECEIPT.ID.eq(id)))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("GoodsReceipt", id);
    }
    GoodsReceipt voided = toReceipt(record);
    voided.setLines(findLines(id));
    return voided;
  }

  @Override
  public boolean existsForSupplier(UUID orgId, UUID supplierId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(GOODS_RECEIPT)
            .where(GOODS_RECEIPT.ORG_ID.eq(orgId).and(GOODS_RECEIPT.SUPPLIER_ID.eq(supplierId))));
  }

  private static GoodsReceipt toReceipt(GoodsReceiptRecord r) {
    GoodsReceipt g = new GoodsReceipt();
    g.setId(r.getId());
    g.setOrgId(r.getOrgId());
    g.setSupplierId(r.getSupplierId());
    g.setReceiptNumber(r.getReceiptNumber());
    g.setStatus(GoodsReceiptStatus.valueOf(r.getStatus()));
    g.setReceivedAt(r.getReceivedAt());
    g.setSupplierReference(r.getSupplierReference());
    g.setNotes(r.getNotes());
    g.setTotalCost(r.getTotalCost());
    g.setIdempotencyKey(r.getIdempotencyKey());
    g.setVoidedAt(r.getVoidedAt());
    g.setVoidReason(r.getVoidReason());
    g.setVoidedBy(r.getVoidedBy());
    g.setCreatedBy(r.getCreatedBy());
    g.setCreatedAt(r.getCreatedAt());
    g.setUpdatedAt(r.getUpdatedAt());
    return g;
  }

  private static GoodsReceiptLine toLine(GoodsReceiptLineRecord r) {
    GoodsReceiptLine l = new GoodsReceiptLine();
    l.setId(r.getId());
    l.setOrgId(r.getOrgId());
    l.setGoodsReceiptId(r.getGoodsReceiptId());
    l.setProductId(r.getProductId());
    l.setQuantity(r.getQuantity());
    l.setUnitCost(r.getUnitCost());
    l.setLineTotal(r.getLineTotal());
    return l;
  }
}
