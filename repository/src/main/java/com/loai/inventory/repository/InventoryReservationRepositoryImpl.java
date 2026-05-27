package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;

import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.repository.generated.tables.records.InventoryReservationRecord;
import java.util.ArrayList;
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
