package com.loai.inventory.domain.repository;

/** Creates a {@link RefundAllocationRepository} bound to a specific transactional context. */
public interface RefundAllocationRepositoryFactory {
  RefundAllocationRepository create(Object ctx);
}
