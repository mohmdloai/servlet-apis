package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Inventory;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {

  Optional<Inventory> findByProductId(UUID orgId, UUID productId);

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
