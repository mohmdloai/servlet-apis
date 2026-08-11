package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the SalesOrder aggregate (order + lines), the per-org order-number counter, and
 * product-snapshot reads needed at order placement.
 *
 * <p>Bound to a transactional {@code DSLContext} via {@link SalesOrderRepositoryFactory}. All
 * methods run inside the caller's transaction.
 */
public interface SalesOrderRepository {

  /**
   * A product looked up at order placement, used to snapshot description + unit price onto lines.
   */
  record ProductSnapshot(UUID productId, String description, BigDecimal unitPrice) {}

  Optional<SalesOrder> findById(UUID orgId, UUID id);

  /**
   * Load an order by id with a row-level write lock ({@code SELECT … FOR UPDATE}). Used by payment
   * reconciliation to serialize against the TTL sweeper's {@code markExpiredIfPending} — whichever
   * transaction grabs the lock first wins, and the loser observes the committed status.
   */
  Optional<SalesOrder> findByIdForUpdate(UUID orgId, UUID id);

  /** Same as {@link #findByIdForUpdate} but keyed by the human-readable {@code order_number}. */
  Optional<SalesOrder> findByOrderNumberForUpdate(UUID orgId, String orderNumber);

  /**
   * Non-locking read by {@code order_number} (e.g. for assembling an idempotent-replay response).
   */
  Optional<SalesOrder> findByOrderNumber(UUID orgId, String orderNumber);

  /**
   * Batch projection {@code id → order_number} for the given orders, scoped to {@code orgId}. Used
   * by list reads that decorate child rows (e.g. fulfillment queue rows) with the human-readable
   * order number without loading whole order aggregates — one query per page, not per row. Ids not
   * in {@code orgId} are simply absent from the result map.
   */
  Map<UUID, String> findOrderNumbersByIds(UUID orgId, Collection<UUID> salesOrderIds);

  /**
   * Persist the payment-related mutable state of an order: {@code status}, {@code prepaid_amount},
   * {@code updated_at}. Scoped by {@code (org_id, id)}.
   */
  void updatePaymentState(SalesOrder order);

  /**
   * Persist the fulfillment-aggregate lifecycle state of an order: {@code status} plus the {@code
   * fulfilled_at} / {@code closed_at} timestamps and {@code updated_at}. Used at delivery when the
   * order rolls up FULFILLING → FULFILLED → CLOSED. Scoped by {@code (org_id, id)}.
   */
  void updateFulfillmentState(SalesOrder order);

  /**
   * Persist the cancellation state of an order: {@code status}, {@code cancelled_at}, the (possibly
   * refund-reduced) {@code prepaid_amount} and {@code updated_at}. Used by order cancellation.
   * Scoped by {@code (org_id, id)}.
   */
  void updateCancelledState(SalesOrder order);

  /** Idempotency short-circuit: returns the prior order if this key has already been used. */
  Optional<SalesOrder> findByIdempotencyKey(UUID orgId, String idempotencyKey);

  List<SalesOrderLine> findLinesByOrderId(UUID salesOrderId);

  /**
   * One page of the org's orders for the worklist. A non-null {@code status} filters and is a queue
   * view (oldest first, {@code created_at ASC}); {@code null} is the unfiltered ledger (newest
   * first, {@code created_at DESC}) — same queue-vs-ledger convention as the payment/refund
   * worklists.
   */
  List<SalesOrder> list(UUID orgId, OrderStatus status, int offset, int limit);

  /** Count of the filtered orders (drives the pager / tab badges). */
  long count(UUID orgId, OrderStatus status);

  /**
   * One page of a single customer's orders for the portal "my orders" read ({@code
   * stories/portal_order_reads.md}) — {@code WHERE org_id=? AND customer_id=?}, newest first
   * ({@code placed_at DESC, id DESC}). Strictly {@code (orgId, customerId)}-scoped; never keyed off
   * a request-supplied customer id. Lines are batch-loaded separately by the caller (no N+1).
   */
  List<SalesOrder> findByCustomerId(UUID orgId, UUID customerId, int offset, int limit);

  /** Count of the customer's orders (drives the portal pager). */
  long countByCustomerId(UUID orgId, UUID customerId);

  /**
   * Batch-load lines for a set of orders, keyed by {@code sales_order_id} — one query per page so
   * the worklist doesn't fetch lines per row. Orders with no lines are absent from the map.
   */
  Map<UUID, List<SalesOrderLine>> findLinesByOrderIds(Collection<UUID> salesOrderIds);

  /**
   * Snapshot lookup for the products on a new order. Returns only products that belong to {@code
   * orgId}; missing ids in the result map signal a 404 to the service layer.
   */
  Map<UUID, ProductSnapshot> fetchProductSnapshots(UUID orgId, Collection<UUID> productIds);

  /**
   * Claim the next sequential order number for {@code (orgId, year)} via {@code INSERT … ON
   * CONFLICT DO UPDATE … RETURNING}. Atomic under concurrent inserts.
   */
  long claimOrderNumber(UUID orgId, int year);

  /**
   * Persist the customer at {@code (org_id, email)}. New row on first sight; otherwise updates
   * name/phone/address using {@code COALESCE(EXCLUDED.x, customer.x)} so a partial payload doesn't
   * erase prior values.
   *
   * <p>{@code locale} is the deliberate exception: it is <b>fill-once</b> ({@code
   * COALESCE(customer.locale, EXCLUDED.locale)} — the stored value wins). A checkout locale is an
   * implicit signal, from whichever link the shopper happened to open, while the portal profile is
   * an explicit setting; letting one English checkout permanently flip an Arabic-speaking
   * customer's language would be the same class of mistake as the delivery contact overwriting
   * their identity (V80).
   */
  Customer upsertCustomerByEmail(
      UUID orgId, String email, String name, String phone, String address, String locale);

  /** Read a customer for response mapping (e.g. on idempotent replay of a prior order). */
  Optional<Customer> findCustomerById(UUID orgId, UUID customerId);

  /** Insert the order + its lines in one go. Both rows are written inside the caller's txn. */
  void insert(SalesOrder order, List<SalesOrderLine> lines);

  /**
   * Find the ids of orders eligible for TTL expiry — {@code status='PENDING_PAYMENT' AND expires_at
   * < now()} — across all orgs, ordered by {@code expires_at ASC}, capped at {@code limit}. Hits
   * the V29 partial index {@code idx_so_pending_global} for an index-ordered scan with no sort
   * step.
   *
   * <p>Returns <b>ids only</b>, never entities: the sweeper re-reads each order under lock anyway,
   * and any richer snapshot would be stale the moment it's read (a payment could land in the same
   * millisecond). This is a read-only projection — the caller runs it on an autocommit connection,
   * never inside a transaction.
   */
  List<UUID> findExpiredPendingIds(int limit);

  /**
   * Atomically flip a single order {@code PENDING_PAYMENT → EXPIRED}, stamping {@code expired_at}
   * and {@code updated_at} with {@code now}. The {@code WHERE status='PENDING_PAYMENT'} clause is
   * the concurrency guard: returns the affected row count — {@code 1} if this call won the flip,
   * {@code 0} if a sibling/payment beat it (the order is no longer PENDING_PAYMENT). No exception
   * in the 0 case — losing the race is normal.
   */
  int markExpiredIfPending(UUID orderId, OffsetDateTime now);
}
