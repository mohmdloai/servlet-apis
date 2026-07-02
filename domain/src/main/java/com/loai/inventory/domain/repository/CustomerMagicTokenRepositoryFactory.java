package com.loai.inventory.domain.repository;

/**
 * Binds a {@link CustomerMagicTokenRepository} to a transaction context (a jOOQ {@code DSLContext},
 * kept as {@code Object} so the domain module stays jOOQ-free). Minting passes the caller's txn;
 * validation passes the root context.
 */
public interface CustomerMagicTokenRepositoryFactory {
  CustomerMagicTokenRepository create(Object ctx);
}
