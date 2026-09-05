package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One browser's push subscription for a staff user (V96, {@code stories/web_push_channel.md}).
 *
 * <p>Keyed on {@code endpoint} — the push service URL, unique per subscription by construction —
 * and <b>not</b> on the Redis session family, which is TTL-bounded and garbage-collected on read.
 * {@code tokenVersionAtSubscribe} is the revocation rule: the subscription is live only while it
 * equals {@code app_user.token_version}, so every existing "sign out everywhere" path (logout-all,
 * password change, de-privilege, platform disable) silences it with no new call site.
 *
 * <p>{@code endpoint}, {@code p256dh} and {@code auth} together are a bearer capability to message
 * this device: no read path returns them and nothing logs the endpoint in full.
 */
public record PushSubscription(
    UUID id,
    UUID userId,
    String endpoint,
    String p256dh,
    String auth,
    String userAgent,
    int tokenVersionAtSubscribe,
    OffsetDateTime createdAt,
    OffsetDateTime lastUsedAt) {}
