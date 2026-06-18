package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.InventoryReservation;
import java.time.OffsetDateTime;
import java.util.Collection;
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

  /**
   * Load every {@code ACTIVE} reservation for {@code orderId} (joining through {@code
   * sales_order_line}, since reservations reference the line, not the order directly), ordered
   * {@code product_id ASC} for stable lock discipline. Returns an empty list for a ghost order with
   * no active reservations.
   */
  List<InventoryReservation> findActiveByOrderId(UUID orderId);

  /** Load reservations by id, ordered {@code product_id ASC}. Used by the fulfillment ship path. */
  List<InventoryReservation> findByIds(Collection<UUID> ids);

  /**
   * Bulk-flip the given reservations to {@code RELEASED}, stamping {@code released_at=now} and
   * {@code released_reason=reason}. Filters on {@code status='ACTIVE'} so a concurrently-consumed
   * row is never re-released. Returns the number of rows actually updated.
   */
  int markReleased(Collection<UUID> ids, String reason, OffsetDateTime now);

  /**
   * Bulk-flip the given reservations to {@code CONSUMED}, stamping {@code consumed_at=now}. Filters
   * on {@code status='ACTIVE'} so a concurrently-released row is never consumed. Returns the number
   * of rows actually updated — the caller checks it equals the expected count.
   */
  int markConsumed(Collection<UUID> ids, OffsetDateTime now);
}
