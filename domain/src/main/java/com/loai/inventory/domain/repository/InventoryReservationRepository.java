package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.ReservationStatus;
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
   * {@code released_reason=reason}. Scoped to {@code orgId} (defense-in-depth — a row is only
   * released within its own tenant) and filtered on {@code status='ACTIVE'} so a concurrently-
   * consumed row is never re-released. Returns the number of rows actually updated.
   */
  int markReleased(UUID orgId, Collection<UUID> ids, String reason, OffsetDateTime now);

  /**
   * Null the {@code expires_at} display mirror on {@code orderId}'s ACTIVE reservations ({@code
   * stories/clear_expiry_on_paid.md}) — called on the PENDING_PAYMENT → PAID flip, in the same
   * transaction, because a paid order has no payment-hold window and V19's "mirrors
   * SalesOrder.expires_at while ACTIVE" contract is only as good as this write site. Moves no
   * stock, flips no status. Returns the number of rows cleared.
   */
  int clearExpiryForOrder(UUID salesOrderId);

  /**
   * Bulk-flip the given reservations to {@code CONSUMED}, stamping {@code consumed_at=now}. Filters
   * on {@code status='ACTIVE'} so a concurrently-released row is never consumed. Returns the number
   * of rows actually updated — the caller checks it equals the expected count.
   */
  int markConsumed(Collection<UUID> ids, OffsetDateTime now);

  /**
   * Every reservation ever created for {@code orderId}, <b>all statuses</b> (ACTIVE / CONSUMED /
   * RELEASED are all part of the order's stock-holds story), joined through {@code
   * sales_order_line} and scoped to {@code orgId} (defense-in-depth). Ordered {@code created_at
   * ASC, id ASC}. Read for the order-detail holds panel ({@code GET
   * /sales-orders/{id}/reservations}).
   */
  List<InventoryReservation> findByOrderId(UUID orgId, UUID orderId);

  /**
   * One reservation for a product plus its order context — {@code sales_order_id} and
   * human-readable {@code order_number} joined through {@code sales_order_line → sales_order} — so
   * the stock detail can itemise {@code reserved_qty} and deep-link each hold. Read for {@code GET
   * /inventory/{productId}/reservations}.
   */
  record ProductReservationRow(
      InventoryReservation reservation, UUID salesOrderId, String salesOrderNumber) {}

  /**
   * The product's reservations in {@code status} (the endpoint defaults to ACTIVE — "what is
   * holding this stock right now"), scoped to {@code orgId}, ordered {@code created_at ASC, id
   * ASC}. Each row carries its order context.
   */
  List<ProductReservationRow> findByProductId(UUID orgId, UUID productId, ReservationStatus status);
}
