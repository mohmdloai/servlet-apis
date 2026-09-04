package com.loai.inventory.service;

import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.InsufficientStockException.Shortage;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reserve stock for a placed online order. Runs inside the caller's transaction — it never opens
 * its own — so a shortage thrown from here rolls back the surrounding {@code placeOnlineOrder}
 * transaction atomically.
 *
 * <p>Three correctness rules from the slice spec ({@code stories/reserve_stock_on_placement.md}):
 *
 * <ol>
 *   <li>Aggregate demand per product so a multi-line same-SKU order locks once, writes one
 *       inventory delta, and produces one log row (still one reservation row per line).
 *   <li>Collect every shortage before throwing — the 409 response lists all short products in one
 *       round-trip rather than one at a time.
 *   <li>A product with no {@code inventory} row is treated as {@code available = 0} (no auto-init
 *       here — matches existing inventory endpoints).
 * </ol>
 */
public final class ReservationService {

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  private final InventoryRepositoryFactory inventoryRepoFactory;
  private final InventoryReservationRepositoryFactory reservationRepoFactory;
  private final InventoryLogRepositoryFactory inventoryLogRepoFactory;
  private final LowStockNotifier lowStockNotifier;

  /** Without the reorder-point check — kept for existing callers and tests. */
  public ReservationService(
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory) {
    this(inventoryRepoFactory, reservationRepoFactory, inventoryLogRepoFactory, null);
  }

  /**
   * @param lowStockNotifier the reorder-point check run after a placement's reservations ({@code
   *     stories/reorder_point.md}); {@code null} = no check (tests that never wire notifications).
   */
  public ReservationService(
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory,
      LowStockNotifier lowStockNotifier) {
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.reservationRepoFactory = reservationRepoFactory;
    this.inventoryLogRepoFactory = inventoryLogRepoFactory;
    this.lowStockNotifier = lowStockNotifier;
  }

  /**
   * Reserve stock for {@code order}. Throws {@link InsufficientStockException} (409) with every
   * shortage if any product is short. Runs in the caller's {@code txDsl} — does not open its own
   * transaction.
   */
  public List<InventoryReservation> reserveForOrder(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      List<SalesOrderLine> lines,
      ActorContext actor) {

    InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
    InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);
    InventoryLogRepository inventoryLogRepo = inventoryLogRepoFactory.create(txDsl);

    // Trap 1: aggregate demand per product so the same SKU on two lines locks the inventory row
    // once and is checked against the *sum* — never as two independent checks.
    Map<UUID, Integer> demandByProduct = new LinkedHashMap<>();
    for (SalesOrderLine line : lines) {
      demandByProduct.merge(line.getProductId(), line.getQuantity(), Integer::sum);
    }

    // Stable ASC lock order (deadlock-safety). lockForUpdate sorts internally too; we sort here
    // so subsequent per-product iteration (update, log) walks the same order for clarity.
    List<UUID> sortedProductIds = demandByProduct.keySet().stream().sorted().toList();

    Map<UUID, Inventory> locked = inventoryRepo.lockForUpdate(orgId, sortedProductIds);

    // Trap 2 + Trap 3: collect every shortage (don't short-circuit on the first), and treat a
    // missing inventory row as available = 0 (no auto-init).
    List<Shortage> shortages = new ArrayList<>();
    for (UUID pid : sortedProductIds) {
      int requested = demandByProduct.get(pid);
      Inventory inv = locked.get(pid);
      int available = (inv == null) ? 0 : inv.getAvailableQty();
      if (available < requested) {
        shortages.add(new Shortage(pid, requested, available));
      }
    }
    if (!shortages.isEmpty()) {
      log.info(
          "Reservation failed for order={} number={} — {} shortage(s)",
          order.getId(),
          order.getOrderNumber(),
          shortages.size());
      throw new InsufficientStockException(shortages);
    }

    // All products are sufficient. Apply per-product inventory writes and log rows.
    List<LowStockNotifier.StockMove> moves = new ArrayList<>(sortedProductIds.size());
    for (UUID pid : sortedProductIds) {
      int totalDelta = demandByProduct.get(pid);
      Inventory current = locked.get(pid); // non-null: shortages was empty
      Inventory updated =
          inventoryRepo.adjustQuantities(orgId, pid, 0, totalDelta, current.getVersion());
      inventoryLogRepo.insert(
          orgId,
          pid,
          0,
          totalDelta,
          updated.getStockQty(),
          updated.getReservedQty(),
          StockReason.RESERVED,
          order.getId(),
          actor);
      moves.add(
          new LowStockNotifier.StockMove(
              pid, current.getAvailableQty(), updated.getAvailableQty()));
    }
    // A reservation is the sale reducing what can be sold — the reorder-point check runs here, in
    // this txn, so a placement that rolls back tells nobody (stories/reorder_point.md).
    if (lowStockNotifier != null) {
      lowStockNotifier.afterSale(txDsl, orgId, moves);
    }

    // One reservation row per sales_order_line (granular audit / link), regardless of aggregation.
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<InventoryReservation> reservations = new ArrayList<>(lines.size());
    for (SalesOrderLine line : lines) {
      reservations.add(
          InventoryReservation.createActive(
              UUID.randomUUID(),
              orgId,
              line.getProductId(),
              line.getId(),
              line.getQuantity(),
              order.getExpiresAt(),
              now));
    }
    reservationRepo.insertAll(reservations);

    log.info(
        "Reserved stock for order={} number={} products={} reservations={}",
        order.getId(),
        order.getOrderNumber(),
        sortedProductIds.size(),
        reservations.size());
    return reservations;
  }

  /** Outcome of a release pass: rows flipped to RELEASED and distinct products touched. */
  public record ReleaseResult(int released, int productsAffected) {
    static final ReleaseResult NONE = new ReleaseResult(0, 0);
  }

  /**
   * Release every ACTIVE reservation for {@code orderId} back to {@code available}: decrement each
   * product's {@code reserved_qty}, write a {@code RELEASED} {@code inventory_log} row, and flip
   * the reservation rows to RELEASED. Runs in the caller's {@code txDsl} — does not open its own
   * transaction. Shared by TTL expiry ({@link OrderExpiryService}) and order cancellation so the
   * {@code reserved_qty == SUM(ACTIVE reservations)} invariant is preserved by one code path.
   *
   * <p>Mirrors the reserve lock discipline: products are locked {@code FOR UPDATE} in ascending id
   * order, so reserve and release never AB/BA-deadlock. A reservation whose inventory row is
   * missing throws (the ground-truth invariant is already broken upstream).
   */
  public ReleaseResult releaseForOrder(
      DSLContext txDsl, UUID orderId, String reason, ActorContext actor, OffsetDateTime now) {

    InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);

    List<InventoryReservation> active = reservationRepo.findActiveByOrderId(orderId);
    return releaseReservations(txDsl, orderId, active, reason, actor, now);
  }

  /**
   * Release a specific set of reservations (by id) back to {@code available} — the targeted variant
   * of {@link #releaseForOrder}, used when cancelling one PENDING fulfillment releases only the
   * reservations its lines link to ({@code fulfillment.md} §CANCELLED) while the order's other
   * reservations stay ACTIVE. Ids that are no longer ACTIVE are skipped. Runs in the caller's
   * {@code txDsl}; {@code orderId} is only used for the {@code inventory_log} source reference.
   */
  public ReleaseResult releaseByIds(
      DSLContext txDsl,
      UUID orderId,
      List<UUID> reservationIds,
      String reason,
      ActorContext actor,
      OffsetDateTime now) {

    InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);

    List<InventoryReservation> active =
        reservationRepo.findByIds(reservationIds).stream()
            .filter(r -> r.getStatus() == com.loai.inventory.domain.model.ReservationStatus.ACTIVE)
            .toList();
    return releaseReservations(txDsl, orderId, active, reason, actor, now);
  }

  /** Shared release body: aggregate per product, lock, adjust, log, flip rows to RELEASED. */
  private ReleaseResult releaseReservations(
      DSLContext txDsl,
      UUID orderId,
      List<InventoryReservation> active,
      String reason,
      ActorContext actor,
      OffsetDateTime now) {

    InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);
    if (active.isEmpty()) {
      return ReleaseResult.NONE;
    }

    // org_id comes from the reservation rows themselves — no separate SELECT on sales_order.
    UUID orgId = active.get(0).getOrgId();

    // Aggregate released qty per product (multi-line same-SKU collapses to one inventory delta and
    // one log row, but every reservation row is still released).
    Map<UUID, Integer> releasedByProduct = new LinkedHashMap<>();
    List<UUID> reservationIds = new ArrayList<>(active.size());
    for (InventoryReservation r : active) {
      releasedByProduct.merge(r.getProductId(), r.getQuantity(), Integer::sum);
      reservationIds.add(r.getId());
    }
    List<UUID> sortedProductIds = releasedByProduct.keySet().stream().sorted().toList();

    InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
    InventoryLogRepository inventoryLogRepo = inventoryLogRepoFactory.create(txDsl);
    Map<UUID, Inventory> locked = inventoryRepo.lockForUpdate(orgId, sortedProductIds);

    // Mutate the canonical aggregate (reserved_qty) and write the audit row per product. The
    // reserved_delta is negative; stock_delta is 0 — nothing physical moved in the warehouse.
    for (UUID pid : sortedProductIds) {
      int total = releasedByProduct.get(pid);
      Inventory current = locked.get(pid);
      if (current == null) {
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
          actor);
    }

    int released = reservationRepo.markReleased(orgId, reservationIds, reason, now);
    log.info(
        "Released {} reservation(s) for order {} across {} product(s) (reason={})",
        released,
        orderId,
        sortedProductIds.size(),
        reason);
    return new ReleaseResult(released, sortedProductIds.size());
  }
}
