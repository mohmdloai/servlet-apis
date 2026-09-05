package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.PushSubscription;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code GET /api/me/push-subscriptions} — the sessions-style "devices with push" read.
 * Deliberately <b>never</b> the endpoint or the keys: together they let anyone message this device,
 * and a listing has no use for them.
 */
public class PushSubscriptionResponse {
  private UUID id;
  private String userAgent;
  private OffsetDateTime createdAt;
  private OffsetDateTime lastUsedAt;

  public static PushSubscriptionResponse from(PushSubscription s) {
    PushSubscriptionResponse r = new PushSubscriptionResponse();
    r.id = s.id();
    r.userAgent = s.userAgent();
    r.createdAt = s.createdAt();
    r.lastUsedAt = s.lastUsedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getLastUsedAt() {
    return lastUsedAt;
  }
}
