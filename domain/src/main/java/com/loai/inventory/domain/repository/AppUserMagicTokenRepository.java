package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.AppUserMagicToken;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@link AppUserMagicToken} — mint and atomically redeem single-use tokens. */
public interface AppUserMagicTokenRepository {

  /** Persist a fresh token (id/createdAt assigned by the DB, echoed back). */
  AppUserMagicToken insert(AppUserMagicToken token);

  /**
   * Atomically redeem a token: stamp {@code consumed_at} iff it is live (unconsumed, unexpired) and
   * of {@code purpose}, returning its {@code user_id}. A single {@code UPDATE ... RETURNING} so two
   * concurrent redeems of the same link cannot both win. Empty for a missing/expired/consumed/wrong
   * -purpose token.
   */
  Optional<UUID> consume(String tokenHash, AppUserTokenPurpose purpose, OffsetDateTime now);
}
