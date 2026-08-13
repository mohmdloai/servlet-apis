package com.loai.inventory.domain.repository;

/** Creates a StorefrontCrawlRepository bound to a specific execution context: transactional ctx. */
public interface StorefrontCrawlRepositoryFactory {
  StorefrontCrawlRepository create(Object ctx);
}
