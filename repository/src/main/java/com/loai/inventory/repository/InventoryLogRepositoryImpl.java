package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.repository.generated.tables.records.InventoryLogRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryLogRepositoryImpl implements InventoryLogRepository {
  private static final Logger log = LoggerFactory.getLogger(InventoryLogRepositoryImpl.class);
  private final DSLContext dsl;

  public InventoryLogRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public InventoryLog insert(
      UUID orgId,
      UUID productId,
      int stockDelta,
      int reservedDelta,
      int stockAfter,
      int reservedAfter,
      StockReason reason,
      UUID orderId,
      ActorContext actor) {

    InventoryLogRecord record =
        baseInsert(
                orgId,
                productId,
                stockDelta,
                reservedDelta,
                stockAfter,
                reservedAfter,
                reason,
                orderId,
                actor,
                null)
            .returning()
            .fetchOne();

    if (record == null) {
      throw new IllegalStateException("INSERT into inventory_log returned no record");
    }

    log.debug(
        "Logged inventory change orgId={} productId={} reason={} stockDelta={} reservedDelta={}",
        orgId,
        productId,
        reason,
        stockDelta,
        reservedDelta);
    return toInventoryLog(record);
  }

  @Override
  public Optional<InventoryLog> insertIdempotent(
      UUID orgId,
      UUID productId,
      int stockDelta,
      int reservedDelta,
      int stockAfter,
      int reservedAfter,
      StockReason reason,
      UUID orderId,
      ActorContext actor,
      String idempotencyKey) {
    // The ledger insert IS the idempotency claim: ON CONFLICT (org_id, idempotency_key) DO NOTHING.
    // A returned record means we won the claim (first use); empty means the key was already
    // recorded
    // (a replay) — the caller then fingerprint-checks and skips the stock move.
    InventoryLogRecord record =
        baseInsert(
                orgId,
                productId,
                stockDelta,
                reservedDelta,
                stockAfter,
                reservedAfter,
                reason,
                orderId,
                actor,
                idempotencyKey)
            .onConflictDoNothing()
            .returning()
            .fetchOne();
    return Optional.ofNullable(record).map(this::toInventoryLog);
  }

  @Override
  public Optional<InventoryLog> findByIdempotencyKey(UUID orgId, String idempotencyKey) {
    return dsl.selectFrom(INVENTORY_LOG)
        .where(INVENTORY_LOG.ORG_ID.eq(orgId).and(INVENTORY_LOG.IDEMPOTENCY_KEY.eq(idempotencyKey)))
        .fetchOptional()
        .map(this::toInventoryLog);
  }

  /** Shared column binding for both the plain and idempotent inserts. */
  private org.jooq.InsertSetMoreStep<InventoryLogRecord> baseInsert(
      UUID orgId,
      UUID productId,
      int stockDelta,
      int reservedDelta,
      int stockAfter,
      int reservedAfter,
      StockReason reason,
      UUID orderId,
      ActorContext actor,
      String idempotencyKey) {
    return dsl.insertInto(INVENTORY_LOG)
        .set(INVENTORY_LOG.ORG_ID, orgId)
        .set(INVENTORY_LOG.PRODUCT_ID, productId)
        .set(INVENTORY_LOG.STOCK_DELTA, stockDelta)
        .set(INVENTORY_LOG.RESERVED_DELTA, reservedDelta)
        .set(INVENTORY_LOG.STOCK_AFTER, stockAfter)
        .set(INVENTORY_LOG.RESERVED_AFTER, reservedAfter)
        .set(
            INVENTORY_LOG.REASON,
            com.loai.inventory.repository.generated.enums.StockReason.lookupLiteral(reason.name()))
        .set(INVENTORY_LOG.ORDER_ID, orderId)
        .set(INVENTORY_LOG.ACTOR_ID, actor != null ? actor.actorId() : null)
        .set(
            INVENTORY_LOG.ACTOR_TYPE,
            actor != null
                ? com.loai.inventory.repository.generated.enums.ActorType.lookupLiteral(
                    actor.actorType().name())
                : null)
        .set(INVENTORY_LOG.IMPERSONATOR_ID, actor != null ? actor.impersonatorId() : null)
        .set(INVENTORY_LOG.IDEMPOTENCY_KEY, idempotencyKey)
        // V101 (stories/general_ledger.md): what a unit cost the moment it moved, read from the
        // product inside the same statement so no caller changes. NULL when uncosted — the ledger
        // poster skips the row and counts it, never prices it at zero.
        .set(
            INVENTORY_LOG.UNIT_COST,
            DSL.select(PRODUCT.COST_PRICE).from(PRODUCT).where(PRODUCT.ID.eq(productId)).asField());
  }

  @Override
  public List<InventoryLog> findByProductId(UUID orgId, UUID productId) {
    return dsl.selectFrom(INVENTORY_LOG)
        .where(INVENTORY_LOG.ORG_ID.eq(orgId).and(INVENTORY_LOG.PRODUCT_ID.eq(productId)))
        .orderBy(INVENTORY_LOG.CREATED_AT.desc())
        .fetch(this::toInventoryLog);
  }

  @Override
  public List<InventoryLog> findByProductId(UUID orgId, UUID productId, int offset, int limit) {
    // Audit ledger — newest first; id DESC is the tiebreak within an equal created_at (same txn).
    return dsl.selectFrom(INVENTORY_LOG)
        .where(INVENTORY_LOG.ORG_ID.eq(orgId).and(INVENTORY_LOG.PRODUCT_ID.eq(productId)))
        .orderBy(INVENTORY_LOG.CREATED_AT.desc(), INVENTORY_LOG.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch(this::toInventoryLog);
  }

  @Override
  public long countByProductId(UUID orgId, UUID productId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(INVENTORY_LOG)
            .where(INVENTORY_LOG.ORG_ID.eq(orgId).and(INVENTORY_LOG.PRODUCT_ID.eq(productId))));
  }

  private InventoryLog toInventoryLog(InventoryLogRecord r) {
    InventoryLog l = new InventoryLog();
    l.setId(r.getId());
    l.setOrgId(r.getOrgId());
    l.setProductId(r.getProductId());
    l.setStockDelta(r.getStockDelta());
    l.setReservedDelta(r.getReservedDelta());
    l.setStockAfter(r.getStockAfter());
    l.setReservedAfter(r.getReservedAfter());
    l.setReason(StockReason.valueOf(r.getReason().getLiteral()));
    l.setOrderId(r.getOrderId());
    l.setActorId(r.getActorId());
    if (r.getActorType() != null) {
      l.setActorType(ActorType.valueOf(r.getActorType().getLiteral()));
    }
    l.setImpersonatorId(r.getImpersonatorId());
    l.setCreatedAt(r.getCreatedAt());
    return l;
  }
}
