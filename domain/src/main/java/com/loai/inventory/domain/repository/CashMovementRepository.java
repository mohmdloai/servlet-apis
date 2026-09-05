package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.CashMovement;
import java.util.List;
import java.util.UUID;

public interface CashMovementRepository {

  void insert(CashMovement movement);

  /** A shift's movements, oldest first. */
  List<CashMovement> findByShift(UUID orgId, UUID shiftId);
}
