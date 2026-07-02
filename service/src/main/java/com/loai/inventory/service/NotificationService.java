package com.loai.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.DeliveryStatus;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationDelivery;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationStatus;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.repository.NotificationRepository;
import com.loai.inventory.domain.repository.NotificationRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notifications: produce (transaction-safe), read/mutate a user's in-app feed, and drain pending
 * deliveries (the worker).
 *
 * <ul>
 *   <li><b>Producer</b> — {@link #notify}/{@link #notifyOrgStaff} take the <em>caller's</em> {@code
 *       DSLContext} so the notification + deliveries are written inside the business transaction: a
 *       rolled-back order leaves no notification. Nothing is enqueued here; the deliveries land
 *       {@code PENDING} and the worker picks them up.
 *   <li><b>Worker</b> — {@link #dispatchPendingInApp} mirrors the order-TTL sweeper: it reads
 *       candidate ids in autocommit, then transitions each delivery in its own short transaction,
 *       so one poison delivery can't roll back its peers. A recurring job drives it (see the api
 *       module). For in-app the insert <em>is</em> the delivery, so dispatch = flip to {@code
 *       SENT}.
 *   <li><b>Feed</b> — own-only reads/mutations, scoped by {@code (orgId, userId)}.
 * </ul>
 *
 * <p>Phase 1 is in-app only. A {@code USER} recipient gets an {@code in_app} delivery; the {@code
 * email} channel (customer recipients) arrives in Phase 2 — until then {@link #notify} rejects a
 * {@code CUSTOMER} recipient loudly rather than dropping it silently.
 */
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  /** "Org staff" for fan-out: everyone who acts on the org (VIEWER is read-only, excluded). */
  private static final Set<OrgRole> STAFF_ROLES =
      Set.of(OrgRole.STAFF, OrgRole.MANAGER, OrgRole.OWNER);

  private final DSLContext rootDsl;
  private final NotificationRepositoryFactory notificationRepoFactory;
  private final UserRepositoryFactory userRepoFactory;
  private final ObjectMapper payloadMapper = new ObjectMapper();

  public NotificationService(
      DSLContext rootDsl,
      NotificationRepositoryFactory notificationRepoFactory,
      UserRepositoryFactory userRepoFactory) {
    this.rootDsl = rootDsl;
    this.notificationRepoFactory = notificationRepoFactory;
    this.userRepoFactory = userRepoFactory;
  }

  /** Result of one sweeper tick. */
  public record DeliverySummary(int picked, int sent, int failed) {}

  // ── Producer (runs inside the caller's business transaction) ────────────────

  /**
   * Fan out one notification per active staff member of {@code orgId} (one row per recipient, per
   * the spec — no bulk table in v1). Runs in the caller's txn.
   */
  public void notifyOrgStaff(
      DSLContext txDsl,
      UUID orgId,
      NotificationType type,
      Map<String, Object> payload,
      String sourceType,
      UUID sourceId,
      String linkTarget) {
    UserRepository users = userRepoFactory.create(txDsl);
    Set<UUID> staff = users.findActiveUserIdsByOrgAndRoles(orgId, STAFF_ROLES);
    for (UUID userId : staff) {
      notify(
          txDsl,
          orgId,
          NotificationRecipient.user(userId),
          type,
          payload,
          sourceType,
          sourceId,
          linkTarget);
    }
    log.debug("Notified {} staff of {} in org {}", staff.size(), type, orgId);
  }

  /**
   * Produce one notification for one recipient: render the template, insert the {@code PENDING}
   * notification + one {@code PENDING} delivery per enabled channel + its subtype row — all in
   * {@code txDsl}. Returns the persisted notification.
   */
  public Notification notify(
      DSLContext txDsl,
      UUID orgId,
      NotificationRecipient recipient,
      NotificationType type,
      Map<String, Object> payload,
      String sourceType,
      UUID sourceId,
      String linkTarget) {
    NotificationRepository repo = notificationRepoFactory.create(txDsl);
    NotificationTemplates.Rendered rendered = NotificationTemplates.render(type, payload);

    Notification n = new Notification();
    n.setOrgId(orgId);
    n.setRecipientType(recipient.type());
    n.setRecipientUserId(recipient.userId());
    n.setRecipientCustomerId(recipient.customerId());
    n.setType(type.name());
    n.setTitle(rendered.title());
    n.setBody(rendered.body());
    n.setSourceType(sourceType);
    n.setSourceId(sourceId);
    n.setStatus(NotificationStatus.PENDING);
    n.setPayloadJson(serialize(payload));
    Notification saved = repo.insertNotification(n);

    for (NotificationChannel channel : channelsFor(recipient)) {
      NotificationDelivery d = new NotificationDelivery();
      d.setNotificationId(saved.getId());
      d.setChannel(channel);
      d.setStatus(DeliveryStatus.PENDING);
      d.setAttempts(0);
      NotificationDelivery savedDelivery = repo.insertDelivery(d);
      if (channel == NotificationChannel.IN_APP) {
        repo.insertInAppDelivery(savedDelivery.getId(), linkTarget);
      }
    }
    return saved;
  }

  /** Phase 1: USER → in_app. CUSTOMER (email) is Phase 2 — reject rather than silently drop. */
  private List<NotificationChannel> channelsFor(NotificationRecipient recipient) {
    return switch (recipient.type()) {
      case USER -> List.of(NotificationChannel.IN_APP);
      case CUSTOMER ->
          throw new UnsupportedOperationException(
              "customer (email) notifications arrive in Phase 2; only in-app is wired");
    };
  }

  // ── Worker: drain pending in-app deliveries ─────────────────────────────────

  public DeliverySummary dispatchPendingInApp(int batchLimit) {
    // Read candidates in autocommit (no long-held txn), then dispatch each in its own transaction.
    NotificationRepository reader = notificationRepoFactory.create(rootDsl);
    List<UUID> ids = reader.findPendingDeliveryIds(NotificationChannel.IN_APP, batchLimit);
    int sent = 0;
    int failed = 0;
    for (UUID id : ids) {
      try {
        dispatchOneInApp(id);
        sent++;
      } catch (RuntimeException e) {
        failed++;
        log.warn("in-app delivery {} failed to dispatch", id, e);
      }
    }
    return new DeliverySummary(ids.size(), sent, failed);
  }

  private void dispatchOneInApp(UUID deliveryId) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          NotificationDelivery d = repo.findDeliveryById(deliveryId).orElse(null);
          if (d == null || d.getStatus() != DeliveryStatus.PENDING) {
            return; // consumed by another tick, or gone
          }
          OffsetDateTime now = now();
          // in-app: the row IS the delivery — nothing to hand to a provider. Mark SENT.
          repo.markDeliverySent(deliveryId, now);
          if (repo.allDeliveriesTerminal(d.getNotificationId())) {
            repo.markNotificationDispatched(d.getNotificationId(), now);
          }
        });
  }

  // ── Feed (own-only) ─────────────────────────────────────────────────────────

  public List<InAppFeedItem> getFeed(
      UUID orgId, UUID userId, boolean unreadOnly, int page, int size) {
    int offset = Pagination.offset(page, size);
    return notificationRepoFactory
        .create(rootDsl)
        .findInAppFeed(orgId, userId, unreadOnly, offset, size);
  }

  public long countFeed(UUID orgId, UUID userId, boolean unreadOnly) {
    return notificationRepoFactory.create(rootDsl).countInAppFeed(orgId, userId, unreadOnly);
  }

  public void markRead(UUID orgId, UUID userId, UUID notificationId) {
    mutateOwn(orgId, userId, notificationId, /* dismiss= */ false);
  }

  public void markDismissed(UUID orgId, UUID userId, UUID notificationId) {
    mutateOwn(orgId, userId, notificationId, /* dismiss= */ true);
  }

  private void mutateOwn(UUID orgId, UUID userId, UUID notificationId, boolean dismiss) {
    rootDsl.transaction(
        cfg -> {
          NotificationRepository repo = notificationRepoFactory.create(DSL.using(cfg));
          OffsetDateTime now = now();
          int updated =
              dismiss
                  ? repo.markInAppDismissed(orgId, userId, notificationId, now)
                  : repo.markInAppRead(orgId, userId, notificationId, now);
          if (updated == 0) {
            throw new NotFoundException("Notification", notificationId);
          }
        });
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String serialize(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    try {
      return payloadMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot serialize notification payload", e);
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
