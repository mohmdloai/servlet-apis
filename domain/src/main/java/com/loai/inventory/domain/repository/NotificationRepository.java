package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationDelivery;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the notification supertable. Split by concern:
 *
 * <ul>
 *   <li><b>Producer</b> ({@code insert*}) — run inside the caller's business transaction so a
 *       rolled-back event leaves no notification.
 *   <li><b>Worker</b> — the delivery sweeper reads candidate ids in autocommit, then transitions
 *       each delivery (and, once its siblings are all terminal, the parent notification) in its own
 *       short transaction.
 *   <li><b>Feed</b> — a user reads / marks / dismisses their own in-app deliveries; every method is
 *       scoped by {@code (orgId, userId)} so callers can only touch their own rows.
 * </ul>
 */
public interface NotificationRepository {

  // ── Producer ──────────────────────────────────────────────────────────────
  Notification insertNotification(Notification notification);

  NotificationDelivery insertDelivery(NotificationDelivery delivery);

  void insertInAppDelivery(UUID deliveryId, String linkTarget);

  // ── Worker (delivery sweeper) ─────────────────────────────────────────────
  List<UUID> findPendingDeliveryIds(NotificationChannel channel, int limit);

  Optional<NotificationDelivery> findDeliveryById(UUID deliveryId);

  void markDeliverySent(UUID deliveryId, OffsetDateTime now);

  void markDeliveryFailed(UUID deliveryId, String lastError, OffsetDateTime now);

  /**
   * True once every delivery of the notification is in a terminal state (SENT/DELIVERED/FAILED).
   */
  boolean allDeliveriesTerminal(UUID notificationId);

  void markNotificationDispatched(UUID notificationId, OffsetDateTime now);

  // ── Feed (own-only) ───────────────────────────────────────────────────────
  List<InAppFeedItem> findInAppFeed(
      UUID orgId, UUID userId, boolean unreadOnly, int offset, int limit);

  long countInAppFeed(UUID orgId, UUID userId, boolean unreadOnly);

  /** Marks the caller's in-app delivery for {@code notificationId} read; returns rows updated. */
  int markInAppRead(UUID orgId, UUID userId, UUID notificationId, OffsetDateTime now);

  /**
   * Marks the caller's in-app delivery for {@code notificationId} dismissed; returns rows updated.
   */
  int markInAppDismissed(UUID orgId, UUID userId, UUID notificationId, OffsetDateTime now);
}
