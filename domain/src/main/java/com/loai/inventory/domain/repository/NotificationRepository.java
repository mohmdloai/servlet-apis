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

  // Producer
  Notification insertNotification(Notification notification);

  NotificationDelivery insertDelivery(NotificationDelivery delivery);

  void insertInAppDelivery(UUID deliveryId, String linkTarget);

  /**
   * The email subtype row for an {@code email} delivery (to-address/subject/HTML frozen at produce
   * time).
   */
  void insertEmailDelivery(UUID deliveryId, String toAddress, String subject, String renderedHtml);

  /**
   * The WhatsApp subtype row (V82). Stores the template INVOCATION rather than a rendered body:
   * Meta accepts a pre-approved template name plus ordered parameters, so the prose on {@code
   * notification.body} cannot be replayed here.
   */
  void insertWhatsAppDelivery(
      UUID deliveryId,
      String toNumber,
      String templateName,
      String templateLanguage,
      String templateParamsJson);

  // Worker (delivery sweeper)
  List<UUID> findPendingDeliveryIds(NotificationChannel channel, int limit);

  /**
   * Claim one delivery for this tick: read it under {@code SELECT … FOR UPDATE SKIP LOCKED}. Empty
   * means either "no such row" or "another tick is already working it" — both are a skip, and the
   * caller cannot tell them apart on purpose.
   *
   * <p>{@code SKIP LOCKED} rather than a plain {@code FOR UPDATE}: two ticks fetch overlapping id
   * sets, and a plain lock made the loser <em>block</em> for the whole of the winner's SMTP
   * round-trip — a second delivery node would spend its tick waiting on rows instead of draining
   * the ones nobody holds. The exclusion the row lock gives (never double-send) is unchanged.
   */
  Optional<NotificationDelivery> findDeliveryById(UUID deliveryId);

  /**
   * Take ownership of a PENDING delivery for sending: {@code PENDING → SENDING} plus a {@code
   * claimed_at} stamp, under the same {@code FOR UPDATE SKIP LOCKED} as {@link #findDeliveryById}.
   * Returns the row as it was when claimed, or empty if it was not PENDING (another tick won it, or
   * it is already terminal).
   *
   * <p>This is what lets the SMTP call happen <b>outside</b> a transaction (D4 follow-up). The
   * claim commits immediately, so no row lock and no pooled connection is held across a round-trip
   * that can take up to 25 s; the state itself, not the lock, is what stops a second worker picking
   * the row up.
   *
   * <p>It deliberately does <b>not</b> touch {@code attempts}. A claim is not an attempt — the
   * settle step counts one, and the reaper counts one for a claim that never settled, so every path
   * out of SENDING increments exactly once.
   */
  Optional<NotificationDelivery> claimForSend(UUID deliveryId, OffsetDateTime now);

  /**
   * Deliveries stranded in SENDING by a worker that died between claiming and settling — {@code
   * claimed_at} older than {@code cutoff}. Without this they would sit SENDING forever, invisible
   * to a sweeper that only looks for PENDING: the lease is what makes a crash recoverable rather
   * than a silent permanent loss.
   */
  List<UUID> findStrandedSendingIds(OffsetDateTime cutoff, int limit);

  void markDeliverySent(UUID deliveryId, OffsetDateTime now);

  void markDeliveryFailed(UUID deliveryId, String lastError, OffsetDateTime now);

  /**
   * Record a failed send attempt and return the delivery to {@code PENDING} so the next sweep
   * retries: {@code attempts++}, {@code last_error} set, {@code claimed_at} cleared. Used by
   * channels the recurring sweeper (not a per-job scheduler) retries — email today.
   *
   * <p>The status write became explicit with the claimed state: the row is SENDING when this is
   * called, so "leave it PENDING" is no longer something that happens by not writing.
   */
  void markDeliveryRetry(UUID deliveryId, String lastError, OffsetDateTime now);

  /** The frozen email content for a delivery (from {@code notification_delivery_email}). */
  Optional<EmailDeliveryContent> findEmailDeliveryContent(UUID deliveryId);

  /** Email fields captured at produce time — what the sweeper hands to the {@code EmailSender}. */
  record EmailDeliveryContent(String toAddress, String subject, String renderedHtml) {}

  /** The frozen WhatsApp invocation for a claimed delivery. */
  Optional<WhatsAppDeliveryContent> findWhatsAppDeliveryContent(UUID deliveryId);

  record WhatsAppDeliveryContent(
      String toNumber, String templateName, String templateLanguage, String templateParamsJson) {}

  /** Record the provider's message id on a sent WhatsApp delivery (null when it returned none). */
  void markWhatsAppProviderMessageId(UUID deliveryId, String providerMessageId);

  /**
   * The org that owns a delivery, via its parent notification. The WhatsApp sweeper needs it to
   * resolve which merchant's credentials to send as — the sending identity is per-org (per-merchant
   * WABA), unlike email's single process-wide SMTP account, and a delivery id is all the sweeper
   * carries.
   */
  Optional<UUID> findOrgIdForDelivery(UUID deliveryId);

  /**
   * True once every delivery of the notification is in a terminal state (SENT/DELIVERED/FAILED).
   */
  boolean allDeliveriesTerminal(UUID notificationId);

  void markNotificationDispatched(UUID notificationId, OffsetDateTime now);

  // Feed (own-only)
  List<InAppFeedItem> findInAppFeed(
      UUID orgId, UUID userId, boolean unreadOnly, int offset, int limit);

  long countInAppFeed(UUID orgId, UUID userId, boolean unreadOnly);

  /** Marks the caller's in-app delivery for {@code notificationId} read; returns rows updated. */
  int markInAppRead(UUID orgId, UUID userId, UUID notificationId, OffsetDateTime now);

  /**
   * Marks the caller's in-app delivery for {@code notificationId} dismissed; returns rows updated.
   */
  int markInAppDismissed(UUID orgId, UUID userId, UUID notificationId, OffsetDateTime now);

  // Customer feed (own-only, the portal plane — slice P5)
  List<InAppFeedItem> findCustomerInAppFeed(
      UUID orgId, UUID customerId, boolean unreadOnly, int offset, int limit);

  long countCustomerInAppFeed(UUID orgId, UUID customerId, boolean unreadOnly);

  /** Marks the customer's own in-app delivery read; returns rows updated (0 = not owned → 404). */
  int markCustomerInAppRead(UUID orgId, UUID customerId, UUID notificationId, OffsetDateTime now);

  /** Marks the customer's own in-app delivery dismissed; returns rows updated. */
  int markCustomerInAppDismissed(
      UUID orgId, UUID customerId, UUID notificationId, OffsetDateTime now);
}
