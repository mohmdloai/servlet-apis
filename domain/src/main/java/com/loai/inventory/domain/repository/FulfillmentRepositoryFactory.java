package com.loai.inventory.domain.repository;

/** Creates a {@link FulfillmentRepository} bound to a specific transactional context. */
public interface FulfillmentRepositoryFactory {
  FulfillmentRepository create(Object ctx);
}
