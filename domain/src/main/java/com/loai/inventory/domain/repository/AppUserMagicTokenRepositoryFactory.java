package com.loai.inventory.domain.repository;

/**
 * Binds an {@link AppUserMagicTokenRepository} to a transaction context (a jOOQ {@code DSLContext},
 * kept as {@code Object} so the domain module stays jOOQ-free). Minting and redeeming both pass the
 * caller's txn so the token change commits/rolls back with the password change beside it.
 */
public interface AppUserMagicTokenRepositoryFactory {
  AppUserMagicTokenRepository create(Object ctx);
}
