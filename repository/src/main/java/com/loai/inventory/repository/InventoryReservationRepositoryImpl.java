package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;

import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.repository.generated.tables.records.InventoryReservationRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryReservationRepositoryImpl implements InventoryReservationRepository {

  private static final Logger log =
      LoggerFactory.getLogger(InventoryReservationRepositoryImpl.class);

  private final DSLContext dsl;

  public InventoryReservationRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insertAll(List<InventoryReservation> reservations) {
    if (reservations == null || reservations.isEmpty()) {
      return;
    }
    List<InventoryReservationRecord> records = new ArrayList<>(reservations.size());
    for (InventoryReservation r : reservations) {
      InventoryReservationRecord rec = dsl.newRecord(INVENTORY_RESERVATION);
      rec.setId(r.getId());
      rec.setOrgId(r.getOrgId());
      rec.setProductId(r.getProductId());
      rec.setSalesOrderLineId(r.getSalesOrderLineId());
      rec.setQuantity(r.getQuantity());
      rec.setStatus(
          com.loai.inventory.repository.generated.enums.ReservationStatus.valueOf(
              r.getStatus().name()));
      rec.setExpiresAt(r.getExpiresAt());
      records.add(rec);
    }
    dsl.batchInsert(records).execute();
    log.debug("Inserted {} inventory_reservation rows", records.size());
  }

  @Override
  public List<InventoryReservation> findBySalesOrderId(UUID salesOrderId) {
    return dsl.select(INVENTORY_RESERVATION.fields())
        .from(INVENTORY_RESERVATION)
        .join(SALES_ORDER_LINE)
        .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
        .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(salesOrderId))
        .fetchInto(INVENTORY_RESERVATION)
        .map(this::toReservation);
  }

  @Override
  public List<InventoryReservation> findActiveByOrderId(UUID orderId) {
    // Reservations reference sales_order_line, not the order — join through the line. ORDER BY
    // product_id ASC matches the inventory FOR UPDATE lock order for deadlock safety.
    return dsl.select(INVENTORY_RESERVATION.fields())
        .from(INVENTORY_RESERVATION)
        .join(SALES_ORDER_LINE)
        .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
        .where(
            SALES_ORDER_LINE
                .SALES_ORDER_ID
                .eq(orderId)
                .and(
                    INVENTORY_RESERVATION.STATUS.eq(
                        com.loai.inventory.repository.generated.enums.ReservationStatus.ACTIVE)))
        .orderBy(INVENTORY_RESERVATION.PRODUCT_ID.asc())
        .fetchInto(INVENTORY_RESERVATION)
        .map(this::toReservation);
  }

  @Override
  public List<InventoryReservation> findByIds(Collection<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(INVENTORY_RESERVATION)
        .where(INVENTORY_RESERVATION.ID.in(ids))
        .orderBy(INVENTORY_RESERVATION.PRODUCT_ID.asc())
        .fetch()
        .map(this::toReservation);
  }

  @Override
  public int markReleased(UUID orgId, Collection<UUID> ids, String reason, OffsetDateTime now) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    // Scope to org (defense-in-depth) and filter on status='ACTIVE' so a concurrently-CONSUMED row
    // is never re-released (Race C).
    return dsl.update(INVENTORY_RESERVATION)
        .set(
            INVENTORY_RESERVATION.STATUS,
            com.loai.inventory.repository.generated.enums.ReservationStatus.RELEASED)
        .set(INVENTORY_RESERVATION.RELEASED_AT, now)
        .set(INVENTORY_RESERVATION.RELEASED_REASON, reason)
        .where(
            INVENTORY_RESERVATION
                .ORG_ID
                .eq(orgId)
                .and(INVENTORY_RESERVATION.ID.in(ids))
                .and(
                    INVENTORY_RESERVATION.STATUS.eq(
                        com.loai.inventory.repository.generated.enums.ReservationStatus.ACTIVE)))
        .execute();
  }

  @Override
  public int clearExpiryForOrder(UUID salesOrderId) {
    return dsl.update(INVENTORY_RESERVATION)
        .setNull(INVENTORY_RESERVATION.EXPIRES_AT)
        .where(
            INVENTORY_RESERVATION
                .SALES_ORDER_LINE_ID
                .in(
                    dsl.select(SALES_ORDER_LINE.ID)
                        .from(SALES_ORDER_LINE)
                        .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(salesOrderId)))
                .and(
                    INVENTORY_RESERVATION.STATUS.eq(
                        com.loai.inventory.repository.generated.enums.ReservationStatus.ACTIVE))
                .and(INVENTORY_RESERVATION.EXPIRES_AT.isNotNull()))
        .execute();
  }

  @Override
  public int markConsumed(Collection<UUID> ids, OffsetDateTime now) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    // Filter on status='ACTIVE' so a concurrently-RELEASED row is never consumed.
    return dsl.update(INVENTORY_RESERVATION)
        .set(
            INVENTORY_RESERVATION.STATUS,
            com.loai.inventory.repository.generated.enums.ReservationStatus.CONSUMED)
        .set(INVENTORY_RESERVATION.CONSUMED_AT, now)
        .where(
            INVENTORY_RESERVATION
                .ID
                .in(ids)
                .and(
                    INVENTORY_RESERVATION.STATUS.eq(
                        com.loai.inventory.repository.generated.enums.ReservationStatus.ACTIVE)))
        .execute();
  }

  @Override
  public List<InventoryReservation> findByOrderId(UUID orgId, UUID orderId) {
    // All statuses (the order's full stock-holds story), joined through sales_order_line since a
    // reservation references the line, not the order. Scoped to org for defense-in-depth.
    return dsl.select(INVENTORY_RESERVATION.fields())
        .from(INVENTORY_RESERVATION)
        .join(SALES_ORDER_LINE)
        .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
        .where(
            SALES_ORDER_LINE.SALES_ORDER_ID.eq(orderId).and(INVENTORY_RESERVATION.ORG_ID.eq(orgId)))
        .orderBy(INVENTORY_RESERVATION.CREATED_AT.asc(), INVENTORY_RESERVATION.ID.asc())
        .fetchInto(INVENTORY_RESERVATION)
        .map(this::toReservation);
  }

  @Override
  public List<ProductReservationRow> findByProductId(
      UUID orgId, UUID productId, ReservationStatus status) {
    // sales_order.id / .order_number collide with inventory_reservation.id by unqualified name in a
    // flat record — alias them and read every field by explicit reference, no into()-by-name
    // mapping.
    var orderIdField = SALES_ORDER.ID.as("so_id");
    var orderNumberField = SALES_ORDER.ORDER_NUMBER.as("so_number");
    return dsl.select(
            INVENTORY_RESERVATION.ID,
            INVENTORY_RESERVATION.ORG_ID,
            INVENTORY_RESERVATION.PRODUCT_ID,
            INVENTORY_RESERVATION.SALES_ORDER_LINE_ID,
            INVENTORY_RESERVATION.QUANTITY,
            INVENTORY_RESERVATION.STATUS,
            INVENTORY_RESERVATION.EXPIRES_AT,
            INVENTORY_RESERVATION.CREATED_AT,
            INVENTORY_RESERVATION.CONSUMED_AT,
            INVENTORY_RESERVATION.RELEASED_AT,
            INVENTORY_RESERVATION.RELEASED_REASON,
            orderIdField,
            orderNumberField)
        .from(INVENTORY_RESERVATION)
        .join(SALES_ORDER_LINE)
        .on(SALES_ORDER_LINE.ID.eq(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID))
        .join(SALES_ORDER)
        .on(SALES_ORDER.ID.eq(SALES_ORDER_LINE.SALES_ORDER_ID))
        .where(
            INVENTORY_RESERVATION
                .ORG_ID
                .eq(orgId)
                .and(INVENTORY_RESERVATION.PRODUCT_ID.eq(productId))
                .and(
                    INVENTORY_RESERVATION.STATUS.eq(
                        com.loai.inventory.repository.generated.enums.ReservationStatus.valueOf(
                            status.name()))))
        .orderBy(INVENTORY_RESERVATION.CREATED_AT.asc(), INVENTORY_RESERVATION.ID.asc())
        .fetch(
            r ->
                new ProductReservationRow(
                    InventoryReservation.rehydrate(
                        r.get(INVENTORY_RESERVATION.ID),
                        r.get(INVENTORY_RESERVATION.ORG_ID),
                        r.get(INVENTORY_RESERVATION.PRODUCT_ID),
                        r.get(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID),
                        r.get(INVENTORY_RESERVATION.QUANTITY),
                        r.get(INVENTORY_RESERVATION.EXPIRES_AT),
                        r.get(INVENTORY_RESERVATION.CREATED_AT),
                        ReservationStatus.valueOf(r.get(INVENTORY_RESERVATION.STATUS).name()),
                        r.get(INVENTORY_RESERVATION.CONSUMED_AT),
                        r.get(INVENTORY_RESERVATION.RELEASED_AT),
                        r.get(INVENTORY_RESERVATION.RELEASED_REASON)),
                    r.get(orderIdField),
                    r.get(orderNumberField)));
  }

  private InventoryReservation toReservation(InventoryReservationRecord r) {
    return InventoryReservation.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getProductId(),
        r.getSalesOrderLineId(),
        r.getQuantity(),
        r.getExpiresAt(),
        r.getCreatedAt(),
        ReservationStatus.valueOf(r.getStatus().name()),
        r.getConsumedAt(),
        r.getReleasedAt(),
        r.getReleasedReason());
  }
}
