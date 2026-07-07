package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single-use capability token emailed to an {@code app_user} for a credential flow (the app_user
 * analog of {@link CustomerMagicToken}). The raw token lives only in the emailed URL; this record
 * holds its SHA-256 hash. Redeeming stamps {@code consumedAt}, so a used token never resolves again.
 *
 * <p>On insert, {@code id}/{@code createdAt} are assigned by the DB and echoed back.
 */
public record AppUserMagicToken(
    UUID id,
    UUID userId,
    String tokenHash,
    AppUserTokenPurpose purpose,
    OffsetDateTime expiresAt,
    OffsetDateTime consumedAt,
    OffsetDateTime createdAt) {

  /** A to-be-persisted token: no id/createdAt/consumedAt yet (the DB assigns id/createdAt). */
  public static AppUserMagicToken forInsert(
      UUID userId, String tokenHash, AppUserTokenPurpose purpose, OffsetDateTime expiresAt) {
    return new AppUserMagicToken(null, userId, tokenHash, purpose, expiresAt, null, null);
  }
}
