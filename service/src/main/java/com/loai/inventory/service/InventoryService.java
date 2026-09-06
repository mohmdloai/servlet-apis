package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryListFilter;
import com.loai.inventory.domain.model.InventoryListStats;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.InventoryStockCounts;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InventoryService {
  private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

  /** Paging bounds for the inventory read lists — mirrors {@code RefundService}. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext rootDsl;
  private final InventoryRepositoryFactory repoFactory;
  private final InventoryLogRepositoryFactory logRepoFactory;
  private final InventoryReservationRepositoryFactory reservationRepoFactory;
  private final ProductRepository productRepository;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;

  public InventoryService(
      DSLContext rootDsl,
      InventoryRepositoryFactory repoFactory,
      InventoryLogRepositoryFactory logRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      ProductRepository productRepository,
      SalesOrderRepositoryFactory salesOrderRepoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.logRepoFactory = logRepoFactory;
    this.reservationRepoFactory = reservationRepoFactory;
    this.productRepository = productRepository;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
  }

  public Inventory getByProductId(UUID orgId, UUID productId) {
    InventoryRepository repo = repoFactory.create(rootDsl);
    return repo.findByProductId(orgId, productId)
        .orElseThrow(() -> new NotFoundException("Inventory", productId));
  }

  // ---- reads (stories/inventory_reads.md) ----

  /**
   * One page of the stock-overview list plus the filtered total (drives the pager) and the filtered
   * set's stock summary ({@code stories/inventory_filters.md}) — all three from one predicate.
   */
  public record OverviewPage(
      List<InventoryRepository.OverviewRow> rows, long total, InventoryListStats stats) {
    /** Rows + total only; the summary is the empty one (pre-slice callers and tests). */
    public OverviewPage(List<InventoryRepository.OverviewRow> rows, long total) {
      this(rows, total, InventoryListStats.empty());
    }
  }

  /**
   * The stock-overview list ({@code GET /inventory}): every product in {@code orgId} LEFT JOINed to
   * its inventory row, so an untracked product surfaces as a row with null stock and {@code
   * tracked=false}. {@code stock=LOW} with no {@code lowLte} defaults the bound to 5 (frontend
   * contract). Read-only on {@code rootDsl}.
   */
  public OverviewPage listOverview(
      UUID orgId, String q, InventoryStockFilter stock, Integer lowLte, int page, int size) {
    return listOverview(orgId, InventoryListFilter.of(q, stock, lowLte), page, size);
  }

  /**
   * As {@link #listOverview(UUID, String, InventoryStockFilter, Integer, int, int)} with the whole
   * {@link InventoryListFilter}: search, tab, category, holds, reorder rule, changed window and
   * sort. The rows, their total and the stock summary come from exactly the same WHERE.
   */
  public OverviewPage listOverview(UUID orgId, InventoryListFilter filter, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    // Guard the unbox trap: `cond ? 5 : lowLte` promotes to int and NPEs on a null lowLte even when
    // the condition is false. Keep both arms Integer.
    Integer bound =
        (filter.stock() == InventoryStockFilter.LOW && filter.lowLte() == null)
            ? Integer.valueOf(5)
            : filter.lowLte();
    InventoryListFilter resolved = filter.withLowLte(bound);
    InventoryRepository repo = repoFactory.create(rootDsl);
    List<InventoryRepository.OverviewRow> rows = repo.listOverview(orgId, resolved, p * s, s);
    InventoryListStats stats = repo.statsOverview(orgId, resolved);
    return new OverviewPage(rows, stats.products(), stats);
  }

  /**
   * The tabs' numbers ({@code GET /inventory/stock-counts}): org totals, one query; {@code lowLte}
   * is the caller's alert level, defaulted to 5 like the LOW tab.
   */
  public InventoryStockCounts stockCounts(UUID orgId, Integer lowLte) {
    return repoFactory.create(rootDsl).stockCounts(orgId, lowLte == null ? 5 : lowLte);
  }

  /** One page of a product's movement ledger + batch-loaded order numbers + the total. */
  public record LogPage(List<InventoryLog> logs, Map<UUID, String> orderNumbers, long total) {}

  /**
   * The movement ledger for one product ({@code GET /inventory/{productId}/log}): the append-only
   * {@code inventory_log} newest-first, each order-linked row's {@code sales_order_number}
   * batch-loaded. 404 if the product is not in {@code orgId}; a tracked-but-never-moved product (or
   * an untracked product that still exists) returns an empty page, not 404.
   */
  public LogPage listLog(UUID orgId, UUID productId, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    requireProductInOrg(orgId, productId);
    InventoryLogRepository logRepo = logRepoFactory.create(rootDsl);
    List<InventoryLog> logs = logRepo.findByProductId(orgId, productId, p * s, s);
    long total = logRepo.countByProductId(orgId, productId);
    Map<UUID, String> orderNumbers =
        salesOrderRepoFactory
            .create(rootDsl)
            .findOrderNumbersByIds(
                orgId,
                logs.stream()
                    .map(InventoryLog::getOrderId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList());
    return new LogPage(logs, orderNumbers, total);
  }

  /** An order's stock holds: the order header + every reservation + batch-loaded product names. */
  public record OrderReservations(
      SalesOrder order, List<InventoryReservation> reservations, Map<UUID, String> productNames) {}

  /**
   * The order-detail holds panel ({@code GET /sales-orders/{id}/reservations}): the order header +
   * every reservation for it (all statuses), each product name batch-loaded. 404 if the order is
   * not in {@code orgId}. Read-only on {@code rootDsl}.
   */
  public OrderReservations listOrderReservations(UUID orgId, UUID orderId) {
    SalesOrder order =
        salesOrderRepoFactory
            .create(rootDsl)
            .findById(orgId, orderId)
            .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
    List<InventoryReservation> reservations =
        reservationRepoFactory.create(rootDsl).findByOrderId(orgId, orderId);
    Map<UUID, String> productNames =
        productRepository.findNamesByIds(
            orgId,
            reservations.stream().map(InventoryReservation::getProductId).distinct().toList());
    return new OrderReservations(order, reservations, productNames);
  }

  /**
   * The per-product active-holds read ({@code GET /inventory/{productId}/reservations?status=}):
   * "what is holding this stock right now", each row carrying its order context. 404 if the product
   * is not in {@code orgId}. Read-only on {@code rootDsl}.
   */
  public List<InventoryReservationRepository.ProductReservationRow> listProductReservations(
      UUID orgId, UUID productId, ReservationStatus status) {
    requireProductInOrg(orgId, productId);
    return reservationRepoFactory.create(rootDsl).findByProductId(orgId, productId, status);
  }

  private void requireProductInOrg(UUID orgId, UUID productId) {
    if (productRepository.findById(orgId, productId).isEmpty()) {
      throw new NotFoundException("Product", productId);
    }
  }

  public Inventory initialise(UUID orgId, UUID productId, int stockQty, ActorContext actor) {
    if (stockQty < 0) throw new ValidationException("stockQty must be >= 0");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          if (repo.existsByProductId(orgId, productId)) {
            throw new ConflictException("Inventory already exists for product: " + productId);
          }

          Inventory inventory = new Inventory();
          inventory.setOrgId(orgId);
          inventory.setProductId(productId);
          inventory.setStockQty(stockQty);

          Inventory saved = repo.insert(inventory);

          logRepo.insert(
              orgId,
              productId,
              stockQty,
              0,
              saved.getStockQty(),
              saved.getReservedQty(),
              StockReason.RESTOCK,
              null,
              actor);

          log.info(
              "Initialised inventory orgId={} productId={} stock={}", orgId, productId, stockQty);
          return saved;
        });
  }

  public Inventory reserve(UUID orgId, UUID productId, int qty, ActorContext actor) {
    return reserve(orgId, productId, qty, null, actor);
  }

  public Inventory reserve(UUID orgId, UUID productId, int qty, UUID orderId, ActorContext actor) {
    validateQty(qty, "reserve");
    validateOrderId(StockReason.RESERVED, orderId);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);

          if (current.getAvailableQty() < qty) {
            throw new ValidationException(
                "Insufficient available stock: available="
                    + current.getAvailableQty()
                    + ", requested="
                    + qty);
          }

          Inventory updated = repo.adjustQuantities(orgId, productId, 0, qty, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              0,
              qty,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.RESERVED,
              orderId,
              actor);

          return updated;
        });
  }

  public Inventory release(UUID orgId, UUID productId, int qty, ActorContext actor) {
    return release(orgId, productId, qty, null, actor);
  }

  public Inventory release(UUID orgId, UUID productId, int qty, UUID orderId, ActorContext actor) {
    validateQty(qty, "release");
    validateOrderId(StockReason.RELEASED, orderId);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);

          if (current.getReservedQty() < qty) {
            throw new ValidationException(
                "Cannot release more than reserved: reserved="
                    + current.getReservedQty()
                    + ", requested="
                    + qty);
          }

          Inventory updated =
              repo.adjustQuantities(orgId, productId, 0, -qty, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              0,
              -qty,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.RELEASED,
              orderId,
              actor);

          return updated;
        });
  }

  public Inventory confirmSale(UUID orgId, UUID productId, int qty, ActorContext actor) {
    return confirmSale(orgId, productId, qty, null, actor);
  }

  public Inventory confirmSale(
      UUID orgId, UUID productId, int qty, UUID orderId, ActorContext actor) {
    validateQty(qty, "confirmSale");
    validateOrderId(StockReason.SOLD, orderId);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);

          if (current.getReservedQty() < qty) {
            throw new ValidationException(
                "Cannot sell more than reserved: reserved="
                    + current.getReservedQty()
                    + ", requested="
                    + qty);
          }

          Inventory updated =
              repo.adjustQuantities(orgId, productId, -qty, -qty, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              -qty,
              -qty,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.SOLD,
              orderId,
              actor);

          return updated;
        });
  }

  public Inventory restock(UUID orgId, UUID productId, int qty, ActorContext actor) {
    return restock(orgId, productId, qty, actor, null);
  }

  /**
   * Restock, optionally idempotent. When {@code idempotencyKey} is non-blank, the {@code +stock} is
   * applied at most once per {@code (org, key)}: the first call records the key and increments; a
   * replay (same key) finds the key already recorded, skips the increment, and returns the
   * already-applied state. This is the seam the deferred offline stock-take queue replays against
   * (backend story {@code product_barcode_lookup.md} §Idempotent restock). A {@code null}/blank key
   * is today's every-call-applies behaviour.
   */
  public Inventory restock(
      UUID orgId, UUID productId, int qty, ActorContext actor, String idempotencyKey) {
    validateQty(qty, "restock");
    String key =
        (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey.trim();

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          // Lock the inventory row FOR UPDATE first (single-row, deadlock-safe): serialises
          // concurrent restocks of this product and yields current qty/version. 404 if untracked.
          Inventory current = repo.lockForUpdate(orgId, List.of(productId)).get(productId);
          if (current == null) {
            throw new NotFoundException("Inventory", productId);
          }

          // Non-idempotent (no key): today's behaviour — apply, then append the ledger row.
          if (key == null) {
            Inventory updated =
                repo.adjustQuantities(orgId, productId, qty, 0, current.getVersion());
            logRepo.insert(
                orgId,
                productId,
                qty,
                0,
                updated.getStockQty(),
                updated.getReservedQty(),
                StockReason.RESTOCK,
                null,
                actor);
            return updated;
          }

          // Idempotent: the ledger insert IS the claim (ON CONFLICT DO NOTHING). stock_after is
          // known
          // from the locked read, so the log is written before the mutation — still one atomic txn.
          int stockAfter = current.getStockQty() + qty;
          Optional<InventoryLog> claimed =
              logRepo.insertIdempotent(
                  orgId,
                  productId,
                  qty,
                  0,
                  stockAfter,
                  current.getReservedQty(),
                  StockReason.RESTOCK,
                  null,
                  actor,
                  key);

          if (claimed.isEmpty()) {
            // Replay: the key was already recorded. Fingerprint the original request — a key reused
            // with a different qty/product is a client bug, surfaced as 409, not silently
            // swallowed.
            InventoryLog prior =
                logRepo
                    .findByIdempotencyKey(orgId, key)
                    .orElseThrow(
                        () -> new IllegalStateException("idempotency key vanished: " + key));
            if (prior.getStockDelta() != qty || !prior.getProductId().equals(productId)) {
              throw new ConflictException(
                  "Idempotency-Key reused with different parameters: " + key);
            }
            log.info(
                "Restock replay ignored (idempotency key already applied) orgId={} productId={}"
                    + " key={}",
                orgId,
                productId,
                key);
            // The locked read already reflects the original (committed) +stock.
            return current;
          }

          // Won the claim → apply the +stock (version matches; we hold the row lock).
          return repo.adjustQuantities(orgId, productId, qty, 0, current.getVersion());
        });
  }

  /**
   * The reasons {@link #adjust} may write ({@code stories/stocktake_count.md}). {@code RESTOCK} has
   * its own action; the order-linked reasons are written by the flows that own their {@code
   * order_id}. Anything else on an adjust would be a lie the ledger cannot detect.
   */
  public static final Set<StockReason> ADJUST_REASONS =
      EnumSet.of(StockReason.ADJUSTMENT, StockReason.STOCKTAKE);

  public Inventory adjust(UUID orgId, UUID productId, int stockDelta, ActorContext actor) {
    return adjust(orgId, productId, stockDelta, StockReason.ADJUSTMENT, actor, null);
  }

  /**
   * Adjust by a signed delta under one of {@link #ADJUST_REASONS}, optionally idempotent — the
   * shape a stocktake posts its variances through ({@code stories/stocktake_count.md}).
   *
   * <p>Mirrors {@link #restock(UUID, UUID, int, ActorContext, String)} exactly: lock the row FOR
   * UPDATE first, then — with a key — the ledger insert IS the claim ({@code ON CONFLICT DO
   * NOTHING}); a replay fingerprints the prior row (delta, product <b>and reason</b>) and either
   * returns the locked current row or 409s. Without a key every call applies, as it always did.
   *
   * <p>The below-reserved guard runs after the lock and before the claim: {@code stock + delta}
   * below {@code reserved_qty} (which covers below zero, since {@code reserved_qty >= 0}) is a
   * {@link ConflictException} naming the reserved count, with nothing written and <b>no key
   * claimed</b> — so a retry with the same key, once the holds are released or the client has
   * re-floored, applies cleanly. The V7 CHECK stays as the backstop it always was.
   */
  public Inventory adjust(
      UUID orgId,
      UUID productId,
      int stockDelta,
      StockReason reason,
      ActorContext actor,
      String idempotencyKey) {
    if (reason == null || !ADJUST_REASONS.contains(reason)) {
      throw new ValidationException("reason must be one of: ADJUSTMENT, STOCKTAKE");
    }
    String key =
        (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey.trim();

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = repo.lockForUpdate(orgId, List.of(productId)).get(productId);
          if (current == null) {
            throw new NotFoundException("Inventory", productId);
          }

          int stockAfter = current.getStockQty() + stockDelta;
          if (stockAfter < current.getReservedQty()) {
            throw new ConflictException(
                "Stock cannot go below the reserved quantity: "
                    + current.getReservedQty()
                    + " units are held by open orders (stock would be "
                    + stockAfter
                    + "). Cancel or fulfil those orders first.");
          }

          if (key == null) {
            Inventory updated =
                repo.adjustQuantities(orgId, productId, stockDelta, 0, current.getVersion());
            logRepo.insert(
                orgId,
                productId,
                stockDelta,
                0,
                updated.getStockQty(),
                updated.getReservedQty(),
                reason,
                null,
                actor);
            return updated;
          }

          Optional<InventoryLog> claimed =
              logRepo.insertIdempotent(
                  orgId,
                  productId,
                  stockDelta,
                  0,
                  stockAfter,
                  current.getReservedQty(),
                  reason,
                  null,
                  actor,
                  key);

          if (claimed.isEmpty()) {
            InventoryLog prior =
                logRepo
                    .findByIdempotencyKey(orgId, key)
                    .orElseThrow(
                        () -> new IllegalStateException("idempotency key vanished: " + key));
            if (prior.getStockDelta() != stockDelta
                || !prior.getProductId().equals(productId)
                || prior.getReason() != reason) {
              throw new ConflictException(
                  "Idempotency-Key reused with different parameters: " + key);
            }
            log.info(
                "Adjust replay ignored (idempotency key already applied) orgId={} productId={}"
                    + " key={}",
                orgId,
                productId,
                key);
            return current;
          }

          return repo.adjustQuantities(orgId, productId, stockDelta, 0, current.getVersion());
        });
  }

  public void delete(UUID orgId, UUID productId, ActorContext actor) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          repo.deleteByProductId(orgId, productId);
          log.info("Deleted inventory orgId={} productId={}", orgId, productId);
        });
  }

  private Inventory findOrThrow(InventoryRepository repo, UUID orgId, UUID productId) {
    return repo.findByProductId(orgId, productId)
        .orElseThrow(() -> new NotFoundException("Inventory", productId));
  }

  private void validateQty(int qty, String operation) {
    if (qty <= 0) {
      throw new ValidationException(operation + " qty must be > 0");
    }
  }

  private void validateOrderId(StockReason reason, UUID orderId) {
    if (reason.requiresOrderId() && orderId == null) {
      throw new ValidationException(reason.getDisplayName() + " requires an orderId");
    }
  }
}
