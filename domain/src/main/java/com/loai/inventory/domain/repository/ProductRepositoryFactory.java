package com.loai.inventory.domain.repository;

/**
 * Creates a ProductRepository bound to a specific execution context: transactional ctx.
 *
 * <p>Added for slice VG1: the variant set-replace mints <b>child product</b> rows inside its own
 * transaction, so it needs a product repository bound to that transaction rather than the
 * long-lived root one.
 */
public interface ProductRepositoryFactory {
  ProductRepository create(Object ctx);
}
