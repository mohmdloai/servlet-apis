package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PushSubscription;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-device Web Push subscriptions of staff users (V96). */
public interface PushSubscriptionRepository {

  /** The row holding this endpoint, whoever it belongs to. */
  Optional<PushSubscription> findByEndpoint(String endpoint);

  /**
   * Subscribe or re-subscribe: upsert on {@code endpoint}. An endpoint already held — by this user
   * or by another account on the same browser profile — is <b>moved</b> to {@code userId} with the
   * keys and {@code tokenVersionAtSubscribe} refreshed; the browser has one subscription per origin
   * and it belongs to whoever is signed in now.
   */
  PushSubscription upsert(
      UUID userId,
      String endpoint,
      String p256dh,
      String auth,
      String userAgent,
      int tokenVersionAtSubscribe);

  /**
   * The user's <em>live</em> subscriptions: the user is active and each row's {@code
   * token_version_at_subscribe} equals the current {@code app_user.token_version}. This is the only
   * read the producer uses — a row that fails the test is silenced, not deleted, so a later
   * re-subscribe from the same browser refreshes it in place.
   */
  List<PushSubscription> findLiveByUser(UUID userId);

  /** Own-only delete by endpoint; returns rows deleted (0 = not the caller's, or never existed). */
  int deleteByUserAndEndpoint(UUID userId, String endpoint);

  /** Prune one subscription (the push service said 404/410). Returns rows deleted. */
  int deleteById(UUID id);

  /** Stamp {@code last_used_at} after a successful send. */
  void touchLastUsed(UUID id, OffsetDateTime now);
}
