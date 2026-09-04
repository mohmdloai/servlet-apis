package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryStockFilter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {

  Optional<Inventory> findByProductId(UUID orgId, UUID productId);

  /**
   * Batch availability read for a set of products in {@code orgId}: {@code productId → available}
   * ({@code stock_qty - reserved_qty}), one query. A product with no {@code inventory} row is
   * <b>absent</b> from the map (the caller treats absent as available 0 / not in stock). Powers the
   * storefront listing page's per-row {@code in_stock} without an N+1. See {@code
   * stories/storefront_availability_signal.md} (B2).
   */
  Map<UUID, Integer> findAvailableByProductIds(UUID orgId, Collection<UUID> productIds);

  /**
   * One row of the stock-overview list: a product LEFT JOINed to its {@code inventory} row. An
   * <b>untracked</b> product (no inventory row) has {@code tracked=false} and null {@code stockQty}
   * / {@code reservedQty} / {@code availableQty} / {@code version} / {@code updatedAt} — the
   * frontend uses the explicit {@code tracked} flag, never infers trackedness from a zero.
   */
  record OverviewRow(
      UUID productId,
      String name,
      String sku,
      BigDecimal basePrice,
      BigDecimal costPrice,
      Integer reorderPoint,
      boolean tracked,
      Integer stockQty,
      Integer reservedQty,
      Integer availableQty,
      Long version,
      OffsetDateTime updatedAt) {}

  /**
   * One page of the stock overview — every product in {@code orgId} LEFT JOINed to inventory,
   * ordered {@code name ASC, product_id ASC} (catalog order — not a queue), except {@code REORDER},
   * the replenishment worklist, which is ordered deepest below its point first. {@code q} is a
   * case-insensitive substring on name OR sku (blank ⇒ no filter). {@code stock} narrows by
   * trackedness / availability ({@code LOW} uses {@code lowLte} as the bound, defaulted upstream).
   * {@code lowLte} is ignored for every filter but {@code LOW}.
   */
  List<OverviewRow> listOverview(
      UUID orgId, String q, InventoryStockFilter stock, Integer lowLte, int offset, int limit);

  /**
   * Count of the filtered stock overview (drives the pager). Same filters as {@link #listOverview}.
   */
  long countOverview(UUID orgId, String q, InventoryStockFilter stock, Integer lowLte);

  Inventory insert(Inventory inventory);

  /**
   * Atomically adjusts stock and reserved quantities using optimistic locking.
   *
   * <p>The update only succeeds if the current version matches {@code expectedVersion}. On success
   * the version is bumped and the updated row is returned. On version mismatch (0 rows updated) a
   * {@code ConflictException} is thrown.
   */
  Inventory adjustQuantities(
      UUID orgId, UUID productId, int stockDelta, int reservedDelta, long expectedVersion);

  /**
   * Pessimistically locks the {@code inventory} rows for the given products with {@code SELECT …
   * FOR UPDATE ORDER BY product_id ASC}. The ASC ordering is the deadlock-safety rule from {@code
   * sys-analysis/outbound/reservation.md} — concurrent placements sharing products must acquire the
   * row locks in the same order.
   *
   * <p>Returned map keys are the {@code productId}s that actually have an {@code inventory} row;
   * callers must treat absent keys as {@code available = 0} (no auto-init at this layer).
   */
  Map<UUID, Inventory> lockForUpdate(UUID orgId, Collection<UUID> productIds);

  void deleteByProductId(UUID orgId, UUID productId);

  boolean existsByProductId(UUID orgId, UUID productId);
}
