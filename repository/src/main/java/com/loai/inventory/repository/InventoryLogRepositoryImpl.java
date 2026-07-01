package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.repository.generated.tables.records.InventoryLogRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
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
        dsl.insertInto(INVENTORY_LOG)
            .set(INVENTORY_LOG.ORG_ID, orgId)
            .set(INVENTORY_LOG.PRODUCT_ID, productId)
            .set(INVENTORY_LOG.STOCK_DELTA, stockDelta)
            .set(INVENTORY_LOG.RESERVED_DELTA, reservedDelta)
            .set(INVENTORY_LOG.STOCK_AFTER, stockAfter)
            .set(INVENTORY_LOG.RESERVED_AFTER, reservedAfter)
            .set(
                INVENTORY_LOG.REASON,
                com.loai.inventory.repository.generated.enums.StockReason.lookupLiteral(
                    reason.name()))
            .set(INVENTORY_LOG.ORDER_ID, orderId)
            .set(INVENTORY_LOG.ACTOR_ID, actor != null ? actor.actorId() : null)
            .set(
                INVENTORY_LOG.ACTOR_TYPE,
                actor != null
                    ? com.loai.inventory.repository.generated.enums.ActorType.lookupLiteral(
                        actor.actorType().name())
                    : null)
            .set(INVENTORY_LOG.IMPERSONATOR_ID, actor != null ? actor.impersonatorId() : null)
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
  public List<InventoryLog> findByProductId(UUID orgId, UUID productId) {
    return dsl.selectFrom(INVENTORY_LOG)
        .where(INVENTORY_LOG.ORG_ID.eq(orgId).and(INVENTORY_LOG.PRODUCT_ID.eq(productId)))
        .orderBy(INVENTORY_LOG.CREATED_AT.desc())
        .fetch(this::toInventoryLog);
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
