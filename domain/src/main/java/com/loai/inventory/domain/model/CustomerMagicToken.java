package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single-resource capability token emailed to a customer (the narrow tier of {@code
 * docs/customer-portal-future.md}). The raw token lives only in the emailed URL; this record holds
 * its SHA-256 hash. Scoped to one {@code resourceId} for one {@link MagicTokenPurpose} — a leaked
 * link exposes one order, never an account.
 *
 * <p>{@code consumedAt} is for one-shot purposes (unsubscribe); {@code VIEW_ORDER} is multi-use
 * until {@code expiresAt} and leaves it null. On insert, {@code id}/{@code createdAt} are assigned
 * by the DB and echoed back.
 */
public record CustomerMagicToken(
    UUID id,
    UUID orgId,
    UUID customerId,
    String tokenHash,
    MagicTokenPurpose purpose,
    UUID resourceId,
    OffsetDateTime expiresAt,
    OffsetDateTime consumedAt,
    OffsetDateTime createdAt) {

  /** A to-be-persisted token: no id/createdAt yet (the DB assigns them). */
  public static CustomerMagicToken forInsert(
      UUID orgId,
      UUID customerId,
      String tokenHash,
      MagicTokenPurpose purpose,
      UUID resourceId,
      OffsetDateTime expiresAt) {
    return new CustomerMagicToken(
        null, orgId, customerId, tokenHash, purpose, resourceId, expiresAt, null, null);
  }
}
