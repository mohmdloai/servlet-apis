package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.StockReason;
import java.util.List;
import java.util.UUID;

public interface InventoryLogRepository {

  InventoryLog insert(
      UUID productId,
      int stockDelta,
      int reservedDelta,
      int stockAfter,
      int reservedAfter,
      StockReason reason,
      UUID orderId,
      ActorContext actor);

  List<InventoryLog> findByProductId(UUID productId);
}
