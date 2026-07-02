package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.CustomerMagicToken;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Persistence for order-scoped magic tokens. Minting runs inside the business txn (so a rolled-back
 * order leaves no token); validation runs in autocommit on the anonymous public route.
 */
public interface CustomerMagicTokenRepository {

  /** Insert a token (id/createdAt assigned by the DB and echoed back). */
  CustomerMagicToken insert(CustomerMagicToken token);

  /**
   * The live token for a hash: unconsumed and not past {@code now}. Returns empty for an unknown,
   * expired, or already-consumed hash — the caller must not distinguish these (no oracle).
   */
  Optional<CustomerMagicToken> findActiveByHash(String tokenHash, OffsetDateTime now);
}
