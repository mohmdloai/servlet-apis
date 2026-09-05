package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.PUSH_SUBSCRIPTION;

import com.loai.inventory.domain.model.PushSubscription;
import com.loai.inventory.domain.repository.PushSubscriptionRepository;
import com.loai.inventory.repository.generated.tables.records.PushSubscriptionRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** jOOQ persistence for {@code push_subscription} (V96). */
public final class PushSubscriptionRepositoryImpl implements PushSubscriptionRepository {

  private final DSLContext dsl;

  public PushSubscriptionRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<PushSubscription> findByEndpoint(String endpoint) {
    return dsl.selectFrom(PUSH_SUBSCRIPTION)
        .where(PUSH_SUBSCRIPTION.ENDPOINT.eq(endpoint))
        .fetchOptional()
        .map(PushSubscriptionRepositoryImpl::toModel);
  }

  @Override
  public PushSubscription upsert(
      UUID userId,
      String endpoint,
      String p256dh,
      String auth,
      String userAgent,
      int tokenVersionAtSubscribe) {
    // ON CONFLICT (endpoint): the browser has ONE subscription per origin and it belongs to whoever
    // is signed in now, so a row held by another account is moved rather than refused. created_at
    // restarts when the owner changes and is kept on a same-user refresh.
    PushSubscriptionRecord r =
        dsl.insertInto(PUSH_SUBSCRIPTION)
            .set(PUSH_SUBSCRIPTION.USER_ID, userId)
            .set(PUSH_SUBSCRIPTION.ENDPOINT, endpoint)
            .set(PUSH_SUBSCRIPTION.P256DH, p256dh)
            .set(PUSH_SUBSCRIPTION.AUTH, auth)
            .set(PUSH_SUBSCRIPTION.USER_AGENT, userAgent)
            .set(PUSH_SUBSCRIPTION.TOKEN_VERSION_AT_SUBSCRIBE, tokenVersionAtSubscribe)
            .onConflict(PUSH_SUBSCRIPTION.ENDPOINT)
            .doUpdate()
            .set(PUSH_SUBSCRIPTION.USER_ID, userId)
            .set(PUSH_SUBSCRIPTION.P256DH, p256dh)
            .set(PUSH_SUBSCRIPTION.AUTH, auth)
            .set(PUSH_SUBSCRIPTION.USER_AGENT, userAgent)
            .set(PUSH_SUBSCRIPTION.TOKEN_VERSION_AT_SUBSCRIBE, tokenVersionAtSubscribe)
            .set(
                PUSH_SUBSCRIPTION.CREATED_AT,
                DSL.when(PUSH_SUBSCRIPTION.USER_ID.ne(userId), DSL.currentOffsetDateTime())
                    .otherwise(PUSH_SUBSCRIPTION.CREATED_AT))
            .set(PUSH_SUBSCRIPTION.LAST_USED_AT, (OffsetDateTime) null)
            .returning()
            .fetchOne();
    if (r == null) {
      throw new IllegalStateException("INSERT into push_subscription returned no record");
    }
    return toModel(r);
  }

  @Override
  public List<PushSubscription> findLiveByUser(UUID userId) {
    // Live = the owner is active AND the row was minted in the CURRENT token generation. A row that
    // fails the second test was silenced by a logout-all / password change / de-privilege and is
    // left in place so the same browser's next re-subscribe refreshes it rather than duplicating.
    return dsl.select(PUSH_SUBSCRIPTION.fields())
        .from(PUSH_SUBSCRIPTION)
        .join(APP_USER)
        .on(APP_USER.ID.eq(PUSH_SUBSCRIPTION.USER_ID))
        .where(PUSH_SUBSCRIPTION.USER_ID.eq(userId))
        .and(APP_USER.ACTIVE.isTrue())
        .and(APP_USER.TOKEN_VERSION.eq(PUSH_SUBSCRIPTION.TOKEN_VERSION_AT_SUBSCRIBE))
        .orderBy(PUSH_SUBSCRIPTION.CREATED_AT.asc())
        .fetch(r -> toModel(r.into(PUSH_SUBSCRIPTION)));
  }

  @Override
  public int deleteByUserAndEndpoint(UUID userId, String endpoint) {
    return dsl.deleteFrom(PUSH_SUBSCRIPTION)
        .where(PUSH_SUBSCRIPTION.USER_ID.eq(userId))
        .and(PUSH_SUBSCRIPTION.ENDPOINT.eq(endpoint))
        .execute();
  }

  @Override
  public int deleteById(UUID id) {
    return dsl.deleteFrom(PUSH_SUBSCRIPTION).where(PUSH_SUBSCRIPTION.ID.eq(id)).execute();
  }

  @Override
  public void touchLastUsed(UUID id, OffsetDateTime now) {
    dsl.update(PUSH_SUBSCRIPTION)
        .set(PUSH_SUBSCRIPTION.LAST_USED_AT, now)
        .where(PUSH_SUBSCRIPTION.ID.eq(id))
        .execute();
  }

  private static PushSubscription toModel(PushSubscriptionRecord r) {
    return new PushSubscription(
        r.getId(),
        r.getUserId(),
        r.getEndpoint(),
        r.getP256dh(),
        r.getAuth(),
        r.getUserAgent(),
        r.getTokenVersionAtSubscribe(),
        r.getCreatedAt(),
        r.getLastUsedAt());
  }
}
