package com.loai.inventory.domain.repository;

/** Creates an {@link InventoryReservationRepository} bound to a transactional context. */
public interface InventoryReservationRepositoryFactory {
  InventoryReservationRepository create(Object ctx);
}
