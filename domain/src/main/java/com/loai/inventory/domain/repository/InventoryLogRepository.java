package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.StockReason;
import java.util.List;
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

  List<InventoryLog> findByProductId(UUID orgId, UUID productId);

  /**
   * One page of the movement ledger for a product, ordered {@code created_at DESC, id DESC} (newest
   * first). Empty for a tracked product that has never moved.
   */
  List<InventoryLog> findByProductId(UUID orgId, UUID productId, int offset, int limit);

  /** Total ledger rows for the product (drives the pager). */
  long countByProductId(UUID orgId, UUID productId);
}
