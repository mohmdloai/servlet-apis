package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT_LINE;

import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentLine;
import com.loai.inventory.domain.model.FulfillmentStatus;
import com.loai.inventory.domain.repository.FulfillmentRepository;
import com.loai.inventory.repository.generated.tables.records.FulfillmentLineRecord;
import com.loai.inventory.repository.generated.tables.records.FulfillmentRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FulfillmentRepositoryImpl implements FulfillmentRepository {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentRepositoryImpl.class);

  private final DSLContext dsl;

  public FulfillmentRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(Fulfillment fulfillment, List<FulfillmentLine> lines) {
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillment.getId())
        .set(FULFILLMENT.ORG_ID, fulfillment.getOrgId())
        .set(FULFILLMENT.SALES_ORDER_ID, fulfillment.getSalesOrderId())
        .set(
            FULFILLMENT.STATUS,
            com.loai.inventory.repository.generated.enums.FulfillmentStatus.valueOf(
                fulfillment.getStatus().name()))
        .set(FULFILLMENT.CARRIER, fulfillment.getCarrier())
        .set(FULFILLMENT.TRACKING_NUMBER, fulfillment.getTrackingNumber())
        .set(FULFILLMENT.NOTES, fulfillment.getNotes())
        .set(FULFILLMENT.SHIPPED_AT, fulfillment.getShippedAt())
        .set(FULFILLMENT.CREATED_AT, fulfillment.getCreatedAt())
        .set(FULFILLMENT.UPDATED_AT, fulfillment.getUpdatedAt())
        .execute();

    if (!lines.isEmpty()) {
      List<FulfillmentLineRecord> records = new ArrayList<>(lines.size());
      for (FulfillmentLine line : lines) {
        FulfillmentLineRecord r = dsl.newRecord(FULFILLMENT_LINE);
        r.setId(line.getId());
        r.setFulfillmentId(line.getFulfillmentId());
        r.setSalesOrderLineId(line.getSalesOrderLineId());
        r.setQuantity(line.getQuantity());
        r.setInventoryReservationId(line.getInventoryReservationId());
        records.add(r);
      }
      dsl.batchInsert(records).execute();
    }

    log.debug(
        "Inserted fulfillment id={} orgId={} salesOrderId={} status={} lines={}",
        fulfillment.getId(),
        fulfillment.getOrgId(),
        fulfillment.getSalesOrderId(),
        fulfillment.getStatus(),
        lines.size());
  }

  @Override
  public Optional<Fulfillment> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(FULFILLMENT)
        .where(FULFILLMENT.ORG_ID.eq(orgId).and(FULFILLMENT.ID.eq(id)))
        .fetchOptional()
        .map(this::toFulfillment);
  }

  @Override
  public Optional<Fulfillment> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(FULFILLMENT)
        .where(FULFILLMENT.ORG_ID.eq(orgId).and(FULFILLMENT.ID.eq(id)))
        .forUpdate()
        .fetchOptional()
        .map(this::toFulfillment);
  }

  @Override
  public List<FulfillmentLine> findLinesByFulfillmentId(UUID fulfillmentId) {
    return dsl.selectFrom(FULFILLMENT_LINE)
        .where(FULFILLMENT_LINE.FULFILLMENT_ID.eq(fulfillmentId))
        .fetch()
        .map(this::toFulfillmentLine);
  }

  @Override
  public void updateStatus(Fulfillment fulfillment) {
    dsl.update(FULFILLMENT)
        .set(
            FULFILLMENT.STATUS,
            com.loai.inventory.repository.generated.enums.FulfillmentStatus.valueOf(
                fulfillment.getStatus().name()))
        .set(FULFILLMENT.SHIPPED_AT, fulfillment.getShippedAt())
        .set(FULFILLMENT.DELIVERED_AT, fulfillment.getDeliveredAt())
        .set(FULFILLMENT.CANCELLED_AT, fulfillment.getCancelledAt())
        .set(FULFILLMENT.FAILED_AT, fulfillment.getFailedAt())
        .set(FULFILLMENT.FAILED_REASON, fulfillment.getFailedReason())
        .set(FULFILLMENT.RETURNED_AT, fulfillment.getReturnedAt())
        .set(FULFILLMENT.UPDATED_AT, fulfillment.getUpdatedAt())
        .where(
            FULFILLMENT
                .ID
                .eq(fulfillment.getId())
                .and(FULFILLMENT.ORG_ID.eq(fulfillment.getOrgId())))
        .execute();
  }

  @Override
  public Map<UUID, Integer> sumFulfilledQtyByOrderLine(UUID salesOrderId) {
    // Join lines to their parent fulfillment so we can filter out CANCELLED shipments (their lines
    // no longer count against the order line's fulfillable quantity).
    Map<UUID, Integer> out = new HashMap<>();
    dsl.select(
            FULFILLMENT_LINE.SALES_ORDER_LINE_ID, org.jooq.impl.DSL.sum(FULFILLMENT_LINE.QUANTITY))
        .from(FULFILLMENT_LINE)
        .join(FULFILLMENT)
        .on(FULFILLMENT.ID.eq(FULFILLMENT_LINE.FULFILLMENT_ID))
        .where(
            FULFILLMENT
                .SALES_ORDER_ID
                .eq(salesOrderId)
                .and(
                    FULFILLMENT.STATUS.ne(
                        com.loai.inventory.repository.generated.enums.FulfillmentStatus.CANCELLED)))
        .groupBy(FULFILLMENT_LINE.SALES_ORDER_LINE_ID)
        .fetch()
        .forEach(r -> out.put(r.value1(), r.value2() == null ? 0 : r.value2().intValue()));
    return out;
  }

  @Override
  public Map<UUID, Integer> sumDeliveredQtyByOrderLine(UUID salesOrderId) {
    // Only DELIVERED fulfillments count toward the order's FULFILLED roll-up: SHIPPED-but-not-yet-
    // delivered lines are still in transit, and CANCELLED ones never shipped.
    Map<UUID, Integer> out = new HashMap<>();
    dsl.select(
            FULFILLMENT_LINE.SALES_ORDER_LINE_ID, org.jooq.impl.DSL.sum(FULFILLMENT_LINE.QUANTITY))
        .from(FULFILLMENT_LINE)
        .join(FULFILLMENT)
        .on(FULFILLMENT.ID.eq(FULFILLMENT_LINE.FULFILLMENT_ID))
        .where(
            FULFILLMENT
                .SALES_ORDER_ID
                .eq(salesOrderId)
                .and(
                    FULFILLMENT.STATUS.eq(
                        com.loai.inventory.repository.generated.enums.FulfillmentStatus.DELIVERED)))
        .groupBy(FULFILLMENT_LINE.SALES_ORDER_LINE_ID)
        .fetch()
        .forEach(r -> out.put(r.value1(), r.value2() == null ? 0 : r.value2().intValue()));
    return out;
  }

  private Fulfillment toFulfillment(FulfillmentRecord r) {
    return Fulfillment.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getSalesOrderId(),
        r.getCreatedAt(),
        FulfillmentStatus.valueOf(r.getStatus().name()),
        r.getCarrier(),
        r.getTrackingNumber(),
        r.getNotes(),
        r.getShippedAt(),
        r.getDeliveredAt(),
        r.getCancelledAt(),
        r.getFailedAt(),
        r.getFailedReason(),
        r.getReturnedAt(),
        r.getUpdatedAt());
  }

  private FulfillmentLine toFulfillmentLine(FulfillmentLineRecord r) {
    return FulfillmentLine.rehydrate(
        r.getId(),
        r.getFulfillmentId(),
        r.getSalesOrderLineId(),
        r.getQuantity(),
        r.getInventoryReservationId());
  }
}
