package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.InventoryReservation;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link InventoryReservation}. Bound to a transactional {@code DSLContext} via
 * {@link InventoryReservationRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface InventoryReservationRepository {

  /** Batch-insert a set of reservations created at order placement. */
  void insertAll(List<InventoryReservation> reservations);

  List<InventoryReservation> findBySalesOrderId(UUID salesOrderId);
}
