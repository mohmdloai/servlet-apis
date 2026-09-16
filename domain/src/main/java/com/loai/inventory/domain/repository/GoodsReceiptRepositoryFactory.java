package com.loai.inventory.domain.repository;

/** Creates a GoodsReceiptRepository bound to a specific execution context: transactional ctx */
public interface GoodsReceiptRepositoryFactory {
  GoodsReceiptRepository create(Object ctx);
}
