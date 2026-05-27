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

  public ReservationService(
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory) {
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.reservationRepoFactory = reservationRepoFactory;
    this.inventoryLogRepoFactory = inventoryLogRepoFactory;
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
}
