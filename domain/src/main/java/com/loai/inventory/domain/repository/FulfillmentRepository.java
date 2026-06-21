package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentLine;
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
