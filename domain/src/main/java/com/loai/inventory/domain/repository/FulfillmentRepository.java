package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentLine;
import com.loai.inventory.domain.model.FulfillmentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the Fulfillment aggregate (fulfillment + lines). Bound to a transactional {@code
 * DSLContext} via {@link FulfillmentRepositoryFactory} — every method runs in the caller's
 * transaction.
 */
public interface FulfillmentRepository {

  /** Insert the fulfillment + its lines in one go, inside the caller's txn. */
  void insert(Fulfillment fulfillment, List<FulfillmentLine> lines);

  Optional<Fulfillment> findById(UUID orgId, UUID id);

  /**
   * Load a fulfillment with a row-level write lock ({@code SELECT … FOR UPDATE}). Used by the ship
   * path so concurrent {@code POST /ship} calls for the same fulfillment serialize on the row.
   */
  Optional<Fulfillment> findByIdForUpdate(UUID orgId, UUID id);

  List<FulfillmentLine> findLinesByFulfillmentId(UUID fulfillmentId);

  /**
   * Lines of every fulfillment in {@code fulfillmentIds}, keyed by fulfillment id — one query for a
   * whole list page instead of one per row. Ids with no lines are absent from the map.
   */
  Map<UUID, List<FulfillmentLine>> findLinesByFulfillmentIds(Collection<UUID> fulfillmentIds);

  /** All fulfillments of {@code salesOrderId}, any status. Non-locking. */
  List<Fulfillment> findByOrderId(UUID orgId, UUID salesOrderId);

  /**
   * One page of the org's fulfillments, optionally filtered by {@code status} ({@code null} = no
   * filter). Filtered = queue view, oldest first ({@code created_at ASC} — the packing/shipping
   * FIFO worklist); unfiltered = ledger, newest first ({@code created_at DESC}). Same
   * queue-vs-ledger split as {@code PaymentTransactionRepository#list}.
   */
  List<Fulfillment> list(UUID orgId, FulfillmentStatus status, int offset, int limit);

  /** Count the fulfillments {@link #list} would return for the same filter. */
  long count(UUID orgId, FulfillmentStatus status);

  /**
   * Atomically cancel a fulfillment iff it is still PENDING — {@code UPDATE … WHERE status =
   * 'PENDING'}; the WHERE clause is the concurrency guard (same pattern as {@code
   * SalesOrderRepository#markExpiredIfPending}). Returns rows updated: {@code 0} means a concurrent
   * ship (or cancel) moved it off PENDING first. Used by the order-cancel cascade, which must not
   * take fulfillment row locks while holding the order lock (lock order is fulfillment → order
   * everywhere else).
   */
  int cancelIfPending(UUID orgId, UUID fulfillmentId, java.time.OffsetDateTime now);

  /**
   * Persist the mutable lifecycle state of a fulfillment: {@code status} and its {@code *_at}
   * timestamps + {@code updated_at}. Scoped by {@code (org_id, id)}.
   */
  void updateStatus(Fulfillment fulfillment);

  /**
   * Sum of fulfilled quantity per {@code sales_order_line_id} across all <b>non-CANCELLED</b>
   * fulfillments of {@code salesOrderId}. Drives the over-fulfillment guard at create time ({@code
   * SUM(fulfillment_line.quantity) <= sales_order_line.quantity}). Lines absent from the map have
   * nothing fulfilled yet.
   */
  Map<UUID, Integer> sumFulfilledQtyByOrderLine(UUID salesOrderId);

  /**
   * Sum of delivered quantity per {@code sales_order_line_id} across all <b>DELIVERED</b>
   * fulfillments of {@code salesOrderId}. Drives the order's FULFILLED roll-up at delivery: the
   * order is FULFILLED only when every line's delivered quantity equals its ordered quantity. Lines
   * absent from the map have nothing delivered yet.
   */
  Map<UUID, Integer> sumDeliveredQtyByOrderLine(UUID salesOrderId);
}
