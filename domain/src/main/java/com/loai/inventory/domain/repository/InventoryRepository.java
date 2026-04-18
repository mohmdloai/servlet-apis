package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Inventory;
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

  void deleteByProductId(UUID orgId, UUID productId);

  boolean existsByProductId(UUID orgId, UUID productId);
}
