package com.loai.inventory.domain.repository;

/** Creates a ListingReviewRepository bound to a specific execution context: transactional ctx */
public interface ListingReviewRepositoryFactory {
  ListingReviewRepository create(Object ctx);
}
