package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.StockReason;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryLogRepository {

  InventoryLog insert(
      UUID orgId,
      UUID productId,
      int stockDelta,
      int reservedDelta,
      int stockAfter,
      int reservedAfter,
      StockReason reason,
      UUID orderId,
      ActorContext actor);

  /**
   * Idempotent ledger insert: writes the row carrying {@code idempotencyKey} with {@code ON
   * CONFLICT (org_id, idempotency_key) DO NOTHING}. Returns the inserted row on first use, or empty
   * when the key was already recorded (a replay). This insert IS the idempotency claim for restock
   * — the caller applies the stock move only when a row comes back. {@code idempotencyKey} must be
   * non-null (the caller decides whether the operation is idempotent).
   */
  Optional<InventoryLog> insertIdempotent(
      UUID orgId,
      UUID productId,
      int stockDelta,
      int reservedDelta,
      int stockAfter,
      int reservedAfter,
      StockReason reason,
      UUID orderId,
      ActorContext actor,
      String idempotencyKey);

  /** The ledger row a key already claimed (for the replay fingerprint check). */
  Optional<InventoryLog> findByIdempotencyKey(UUID orgId, String idempotencyKey);

  List<InventoryLog> findByProductId(UUID orgId, UUID productId);

  /**
   * One page of the movement ledger for a product, ordered {@code created_at DESC, id DESC} (newest
   * first). Empty for a tracked product that has never moved.
   */
  List<InventoryLog> findByProductId(UUID orgId, UUID productId, int offset, int limit);

  /** Total ledger rows for the product (drives the pager). */
  long countByProductId(UUID orgId, UUID productId);
}
