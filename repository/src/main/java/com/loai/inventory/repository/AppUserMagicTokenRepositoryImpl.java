package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.APP_USER_MAGIC_TOKEN;

import com.loai.inventory.domain.model.AppUserMagicToken;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import com.loai.inventory.domain.repository.AppUserMagicTokenRepository;
import com.loai.inventory.repository.generated.tables.records.AppUserMagicTokenRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** jOOQ persistence for app_user credential tokens. {@code purpose} is TEXT (enum {@code name()}). */
public final class AppUserMagicTokenRepositoryImpl implements AppUserMagicTokenRepository {

  private final DSLContext dsl;

  public AppUserMagicTokenRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public AppUserMagicToken insert(AppUserMagicToken token) {
    AppUserMagicTokenRecord r =
        dsl.insertInto(APP_USER_MAGIC_TOKEN)
            .set(APP_USER_MAGIC_TOKEN.USER_ID, token.userId())
            .set(APP_USER_MAGIC_TOKEN.TOKEN_HASH, token.tokenHash())
            .set(APP_USER_MAGIC_TOKEN.PURPOSE, token.purpose().name())
            .set(APP_USER_MAGIC_TOKEN.EXPIRES_AT, token.expiresAt())
            .returning()
            .fetchOne();
    if (r == null) {
      throw new IllegalStateException("INSERT into app_user_magic_token returned no record");
    }
    return toToken(r);
  }

  @Override
  public Optional<UUID> consume(String tokenHash, AppUserTokenPurpose purpose, OffsetDateTime now) {
    // Compare-and-swap: only an unconsumed, unexpired, right-purpose row flips, and RETURNING hands
    // back its user_id. Two concurrent redeems race on the same row; exactly one gets a result.
    return dsl.update(APP_USER_MAGIC_TOKEN)
        .set(APP_USER_MAGIC_TOKEN.CONSUMED_AT, now)
        .where(APP_USER_MAGIC_TOKEN.TOKEN_HASH.eq(tokenHash))
        .and(APP_USER_MAGIC_TOKEN.PURPOSE.eq(purpose.name()))
        .and(APP_USER_MAGIC_TOKEN.CONSUMED_AT.isNull())
        .and(APP_USER_MAGIC_TOKEN.EXPIRES_AT.gt(now))
        .returningResult(APP_USER_MAGIC_TOKEN.USER_ID)
        .fetchOptional()
        .map(org.jooq.Record1::value1);
  }

  private AppUserMagicToken toToken(AppUserMagicTokenRecord r) {
    return new AppUserMagicToken(
        r.getId(),
        r.getUserId(),
        r.getTokenHash(),
        AppUserTokenPurpose.valueOf(r.getPurpose()),
        r.getExpiresAt(),
        r.getConsumedAt(),
        r.getCreatedAt());
  }
}
