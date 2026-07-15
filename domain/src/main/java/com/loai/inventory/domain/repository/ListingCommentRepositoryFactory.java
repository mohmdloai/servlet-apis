package com.loai.inventory.domain.repository;

/** Creates a ListingCommentRepository bound to a specific execution context: transactional ctx */
public interface ListingCommentRepositoryFactory {
  ListingCommentRepository create(Object ctx);
}
