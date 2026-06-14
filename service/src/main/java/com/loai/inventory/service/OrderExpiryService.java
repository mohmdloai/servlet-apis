package com.loai.inventory.service;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expire {@code PENDING_PAYMENT} orders whose {@code expires_at} has passed and release their stock
 * reservations back to {@code available}. Implements Strategy 1 from {@code
 * sys-analysis/outbound/reservation.md} §Expiry — the reservation has no clock of its own;
 * releasing it is purely a downstream effect of the order expiring.
 *
 * <p>The driving design rule of this slice — see {@code stories/expire_pending_orders.md} "The
 * poison-pill rule": {@link #sweep(int)} <b>must not</b> open a transaction. Each order is expired
 * in its <b>own</b> short transaction via {@link #expireOnePending(UUID)} so that one poison order
 * cannot roll back its 199 healthy peers, and so the per-order {@code FOR UPDATE} contention window
 * stays in the millisecond range instead of spanning the whole batch.
 */
public final class OrderExpiryService {

  private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

  /** Free-text reason written to {@code inventory_reservation.released_reason} by this slice. */
  private static final String RELEASE_REASON_EXPIRED = "EXPIRED";

  /** Audit actor for every {@code inventory_log} row written by the sweeper. */
  private static final ActorContext SWEEPER_ACTOR = ActorContext.system("order-ttl-sweeper");

  private final DSLContext rootDsl;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final InventoryRepositoryFactory inventoryRepoFactory;
  private final InventoryReservationRepositoryFactory reservationRepoFactory;
  private final InventoryLogRepositoryFactory inventoryLogRepoFactory;

  public OrderExpiryService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory) {
    this.rootDsl = rootDsl;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.reservationRepoFactory = reservationRepoFactory;
    this.inventoryLogRepoFactory = inventoryLogRepoFactory;
  }

  /** Aggregate counts returned by one {@link #sweep(int)} pass. */
  public record Summary(
      int candidatesScanned,
      int ordersExpired,
      int reservationsReleased,
      int productsAffected,
      int orderFailures) {}

  /** Per-order outcome accumulated by {@link #sweep(int)}. */
  private record PerOrderResult(int flipped, int reservationsReleased, int productsAffected) {
    static final PerOrderResult NOOP = new PerOrderResult(0, 0, 0);
  }

  /**
   * Expire orders whose {@code expires_at} is in the past — one short transaction per order. Stops
   * at {@code batchLimit}; the next tick picks up the rest.
   *
   * <p><b>This method MUST NOT open a transaction.</b> The candidate read is an autocommit
   * id-projection; each order is then committed independently by {@link #expireOnePending(UUID)}.
   * Every per-order failure is caught and logged so a single poison order cannot poison the whole
   * sweep — the failed order's row is left untouched and re-tried on the next tick.
   */
  public Summary sweep(int batchLimit) {
    SalesOrderRepository repo = salesOrderRepoFactory.create(rootDsl);
    List<UUID> candidates = repo.findExpiredPendingIds(batchLimit);

    int expired = 0;
    int reservationsReleased = 0;
    int productsAffected = 0;
    int failures = 0;

    for (UUID orderId : candidates) {
      try {
        PerOrderResult r = doExpire(orderId);
        expired += r.flipped();
        reservationsReleased += r.reservationsReleased();
        productsAffected += r.productsAffected();
      } catch (RuntimeException ex) {
        // Load-bearing catch: isolate the failure to this order so the loop continues and the
        // healthy candidates still commit. The bad order is unchanged in the DB and will be
        // re-found next tick. Do NOT narrow, rethrow, or remove this — see the poison-pill rule.
        failures++;
        log.warn("Sweeper failed to expire order {} — skipping; will retry next tick", orderId, ex);
      }
    }

    Summary summary =
        new Summary(candidates.size(), expired, reservationsReleased, productsAffected, failures);
    log.info("Sweeper sweep complete: {}", summary);
    return summary;
  }

  /**
   * Expire a single order in its own short transaction. Idempotent: returns {@code 0} if the order
   * was already paid/cancelled/expired by the time the transaction opened, {@code 1} if this call
   * performed the flip.
   *
   * <p><b>Unified timestamp discipline:</b> a single {@code now} is captured at the top of the
   * transaction and threaded through {@code sales_order.expired_at} and {@code
   * inventory_reservation.released_at} so a future oncall can group every write from one expiry by
   * exact-equal timestamp.
   */
  public int expireOnePending(UUID orderId) {
    return doExpire(orderId).flipped();
  }

  /** The per-order transaction body shared by {@link #sweep} and {@link #expireOnePending}. */
  private PerOrderResult doExpire(UUID orderId) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          SalesOrderRepository salesOrderRepo = salesOrderRepoFactory.create(txDsl);
          InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);

          // Step 1 — atomic structural flip; the WHERE status='PENDING_PAYMENT' IS the guard.
          int flipped = salesOrderRepo.markExpiredIfPending(orderId, now);
          if (flipped == 0) {
            // A sibling sweeper or a payment already moved the order off PENDING_PAYMENT (Race
            // A/B).
            // Normal — no reservations touched.
            log.debug("Order {} no longer PENDING_PAYMENT at expiry time — noop", orderId);
            return PerOrderResult.NOOP;
          }

          // Step 2 — load the ACTIVE reservations for the order (joined via sales_order_line).
          List<InventoryReservation> active = reservationRepo.findActiveByOrderId(orderId);
          if (active.isEmpty()) {
            // Ghost order: flipped cleanly, but nothing to release. Steps 3–7 skipped, no
            // inventory rows locked, no inventory_log row written.
            log.debug("Order {} expired with zero active reservations (ghost)", orderId);
            return new PerOrderResult(1, 0, 0);
          }

          // org_id comes from the reservation rows themselves — no separate SELECT on sales_order.
          UUID orgId = active.get(0).getOrgId();

          // Step 3 — aggregate released qty per product (multi-line same-SKU collapses to one
          // inventory delta and one log row, but every reservation row is still released).
          Map<UUID, Integer> releasedByProduct = new LinkedHashMap<>();
          List<UUID> reservationIds = new ArrayList<>(active.size());
          for (InventoryReservation r : active) {
            releasedByProduct.merge(r.getProductId(), r.getQuantity(), Integer::sum);
            reservationIds.add(r.getId());
          }
          List<UUID> sortedProductIds = releasedByProduct.keySet().stream().sorted().toList();

          // Step 4 — lock inventory rows ORDER BY product_id ASC (slice-2 primitive; same lock
          // order as placeOnlineOrder, so the two never AB/BA-deadlock).
          InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
          InventoryLogRepository inventoryLogRepo = inventoryLogRepoFactory.create(txDsl);
          Map<UUID, Inventory> locked = inventoryRepo.lockForUpdate(orgId, sortedProductIds);

          // Steps 5 + 7 — mutate the canonical aggregate (reserved_qty) and write the audit row,
          // per product, BEFORE marking the child reservation rows (step 6). reserved_delta is
          // negative; stock_delta is 0 — nothing physical moved in the warehouse.
          for (UUID pid : sortedProductIds) {
            int total = releasedByProduct.get(pid);
            Inventory current = locked.get(pid);
            if (current == null) {
              // A reservation exists without its inventory row — the ground-truth invariant is
              // already broken upstream. Throw so this one order rolls back and is surfaced.
              throw new IllegalStateException(
                  "no inventory row for product "
                      + pid
                      + " (org "
                      + orgId
                      + ") while releasing order "
                      + orderId);
            }
            Inventory updated =
                inventoryRepo.adjustQuantities(orgId, pid, 0, -total, current.getVersion());
            inventoryLogRepo.insert(
                orgId,
                pid,
                0,
                -total,
                updated.getStockQty(),
                updated.getReservedQty(),
                StockReason.RELEASED,
                orderId,
                SWEEPER_ACTOR);
          }

          // Step 6 — mark every ACTIVE reservation for the order RELEASED.
          int released = reservationRepo.markReleased(reservationIds, RELEASE_REASON_EXPIRED, now);

          log.info(
              "Expired order {} — released {} reservation(s) across {} product(s)",
              orderId,
              released,
              sortedProductIds.size());
          return new PerOrderResult(1, released, sortedProductIds.size());
        });
  }
}
