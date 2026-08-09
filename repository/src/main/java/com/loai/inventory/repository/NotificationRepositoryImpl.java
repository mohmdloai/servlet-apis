package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_EMAIL;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_IN_APP;

import com.loai.inventory.domain.model.DeliveryStatus;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationDelivery;
import com.loai.inventory.domain.model.NotificationStatus;
import com.loai.inventory.domain.model.RecipientType;
import com.loai.inventory.domain.repository.NotificationRepository;
import com.loai.inventory.repository.generated.tables.records.NotificationDeliveryRecord;
import com.loai.inventory.repository.generated.tables.records.NotificationRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jOOQ persistence for the notification supertable. Enum columns are TEXT; see the enum bridges.
 */
public final class NotificationRepositoryImpl implements NotificationRepository {

  private static final Logger log = LoggerFactory.getLogger(NotificationRepositoryImpl.class);
  private static final List<String> TERMINAL = List.of("SENT", "DELIVERED", "FAILED");

  private final DSLContext dsl;

  public NotificationRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  // Producer

  @Override
  public Notification insertNotification(Notification n) {
    NotificationRecord r =
        dsl.insertInto(NOTIFICATION)
            .set(NOTIFICATION.ORG_ID, n.getOrgId())
            .set(NOTIFICATION.RECIPIENT_TYPE, n.getRecipientType().name())
            .set(NOTIFICATION.RECIPIENT_USER_ID, n.getRecipientUserId())
            .set(NOTIFICATION.RECIPIENT_CUSTOMER_ID, n.getRecipientCustomerId())
            .set(NOTIFICATION.TYPE, n.getType())
            .set(NOTIFICATION.TITLE, n.getTitle())
            .set(NOTIFICATION.BODY, n.getBody())
            .set(NOTIFICATION.SOURCE_TYPE, n.getSourceType())
            .set(NOTIFICATION.SOURCE_ID, n.getSourceId())
            .set(NOTIFICATION.STATUS, n.getStatus().name())
            .set(
                NOTIFICATION.PAYLOAD,
                n.getPayloadJson() == null ? null : JSONB.valueOf(n.getPayloadJson()))
            .returning()
            .fetchOne();
    if (r == null) {
      throw new IllegalStateException("INSERT into notification returned no record");
    }
    log.debug("Inserted notification id={} orgId={} type={}", r.getId(), r.getOrgId(), r.getType());
    return toNotification(r);
  }

  @Override
  public NotificationDelivery insertDelivery(NotificationDelivery d) {
    NotificationDeliveryRecord r =
        dsl.insertInto(NOTIFICATION_DELIVERY)
            .set(NOTIFICATION_DELIVERY.NOTIFICATION_ID, d.getNotificationId())
            .set(NOTIFICATION_DELIVERY.CHANNEL, d.getChannel().dbValue())
            .set(NOTIFICATION_DELIVERY.STATUS, d.getStatus().name())
            .set(NOTIFICATION_DELIVERY.ATTEMPTS, d.getAttempts())
            .returning()
            .fetchOne();
    if (r == null) {
      throw new IllegalStateException("INSERT into notification_delivery returned no record");
    }
    return toDelivery(r);
  }

  @Override
  public void insertInAppDelivery(UUID deliveryId, String linkTarget) {
    dsl.insertInto(NOTIFICATION_DELIVERY_IN_APP)
        .set(NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID, deliveryId)
        .set(NOTIFICATION_DELIVERY_IN_APP.LINK_TARGET, linkTarget)
        .execute();
  }

  @Override
  public void insertEmailDelivery(
      UUID deliveryId, String toAddress, String subject, String renderedHtml) {
    dsl.insertInto(NOTIFICATION_DELIVERY_EMAIL)
        .set(NOTIFICATION_DELIVERY_EMAIL.DELIVERY_ID, deliveryId)
        .set(NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS, toAddress)
        .set(NOTIFICATION_DELIVERY_EMAIL.SUBJECT, subject)
        .set(NOTIFICATION_DELIVERY_EMAIL.RENDERED_HTML, renderedHtml)
        .execute();
  }

  // Worker

  @Override
  public List<UUID> findPendingDeliveryIds(NotificationChannel channel, int limit) {
    return dsl.select(NOTIFICATION_DELIVERY.ID)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.CHANNEL.eq(channel.dbValue()))
        .and(NOTIFICATION_DELIVERY.STATUS.eq(DeliveryStatus.PENDING.name()))
        .orderBy(NOTIFICATION_DELIVERY.CREATED_AT.asc())
        .limit(limit)
        .fetch(NOTIFICATION_DELIVERY.ID);
  }

  @Override
  public Optional<NotificationDelivery> findDeliveryById(UUID deliveryId) {
    // FOR UPDATE: the delivery sweeper locks the row so two concurrent ticks that both picked this
    // id can never both send it. SKIP LOCKED: the loser returns empty and moves on to the next
    // delivery instead of blocking for the length of the winner's SMTP round-trip.
    return dsl.selectFrom(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .forUpdate()
        .skipLocked()
        .fetchOptional()
        .map(this::toDelivery);
  }

  @Override
  public void markDeliverySent(UUID deliveryId, OffsetDateTime now) {
    dsl.update(NOTIFICATION_DELIVERY)
        .set(NOTIFICATION_DELIVERY.STATUS, DeliveryStatus.SENT.name())
        .set(NOTIFICATION_DELIVERY.ATTEMPTS, NOTIFICATION_DELIVERY.ATTEMPTS.plus(1))
        .set(NOTIFICATION_DELIVERY.SENT_AT, now)
        .set(NOTIFICATION_DELIVERY.UPDATED_AT, now)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .execute();
  }

  @Override
  public void markDeliveryFailed(UUID deliveryId, String lastError, OffsetDateTime now) {
    dsl.update(NOTIFICATION_DELIVERY)
        .set(NOTIFICATION_DELIVERY.STATUS, DeliveryStatus.FAILED.name())
        .set(NOTIFICATION_DELIVERY.ATTEMPTS, NOTIFICATION_DELIVERY.ATTEMPTS.plus(1))
        .set(NOTIFICATION_DELIVERY.LAST_ERROR, lastError)
        .set(NOTIFICATION_DELIVERY.FAILED_AT, now)
        .set(NOTIFICATION_DELIVERY.UPDATED_AT, now)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .execute();
  }

  @Override
  public Optional<NotificationDelivery> claimForSend(UUID deliveryId, OffsetDateTime now) {
    // Lock exactly as findDeliveryById does, then flip PENDING -> SENDING in the same transaction.
    // The caller commits immediately, so the claim outlives the lock: from then on it is the STATE
    // that keeps other workers off this row, which is what frees the send from the transaction.
    Optional<NotificationDelivery> locked =
        dsl.selectFrom(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
            .and(NOTIFICATION_DELIVERY.STATUS.eq(DeliveryStatus.PENDING.name()))
            .forUpdate()
            .skipLocked()
            .fetchOptional()
            .map(this::toDelivery);
    if (locked.isEmpty()) {
      return Optional.empty();
    }
    dsl.update(NOTIFICATION_DELIVERY)
        .set(NOTIFICATION_DELIVERY.STATUS, DeliveryStatus.SENDING.name())
        .set(NOTIFICATION_DELIVERY.CLAIMED_AT, now)
        .set(NOTIFICATION_DELIVERY.UPDATED_AT, now)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .execute();
    return locked;
  }

  @Override
  public List<UUID> findStrandedSendingIds(OffsetDateTime cutoff, int limit) {
    return dsl.select(NOTIFICATION_DELIVERY.ID)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.STATUS.eq(DeliveryStatus.SENDING.name()))
        .and(NOTIFICATION_DELIVERY.CLAIMED_AT.lt(cutoff))
        .orderBy(NOTIFICATION_DELIVERY.CLAIMED_AT.asc())
        .limit(limit)
        .fetch(NOTIFICATION_DELIVERY.ID);
  }

  @Override
  public void markDeliveryRetry(UUID deliveryId, String lastError, OffsetDateTime now) {
    // Back to PENDING so the next sweep retries. The status write is explicit now that the row
    // arrives here SENDING — before the claimed state, "still PENDING" was the absence of a write.
    dsl.update(NOTIFICATION_DELIVERY)
        .set(NOTIFICATION_DELIVERY.STATUS, DeliveryStatus.PENDING.name())
        .set(NOTIFICATION_DELIVERY.CLAIMED_AT, (OffsetDateTime) null)
        .set(NOTIFICATION_DELIVERY.ATTEMPTS, NOTIFICATION_DELIVERY.ATTEMPTS.plus(1))
        .set(NOTIFICATION_DELIVERY.LAST_ERROR, lastError)
        .set(NOTIFICATION_DELIVERY.UPDATED_AT, now)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .execute();
  }

  @Override
  public Optional<EmailDeliveryContent> findEmailDeliveryContent(UUID deliveryId) {
    return dsl.select(
            NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS,
            NOTIFICATION_DELIVERY_EMAIL.SUBJECT,
            NOTIFICATION_DELIVERY_EMAIL.RENDERED_HTML)
        .from(NOTIFICATION_DELIVERY_EMAIL)
        .where(NOTIFICATION_DELIVERY_EMAIL.DELIVERY_ID.eq(deliveryId))
        .fetchOptional()
        .map(
            r ->
                new EmailDeliveryContent(
                    r.get(NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS),
                    r.get(NOTIFICATION_DELIVERY_EMAIL.SUBJECT),
                    r.get(NOTIFICATION_DELIVERY_EMAIL.RENDERED_HTML)));
  }

  @Override
  public boolean allDeliveriesTerminal(UUID notificationId) {
    // No pending/unattempted delivery remains for this notification.
    return !dsl.fetchExists(
        dsl.selectOne()
            .from(NOTIFICATION_DELIVERY)
            .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
            .and(NOTIFICATION_DELIVERY.STATUS.notIn(TERMINAL)));
  }

  @Override
  public void markNotificationDispatched(UUID notificationId, OffsetDateTime now) {
    dsl.update(NOTIFICATION)
        .set(NOTIFICATION.STATUS, NotificationStatus.DISPATCHED.name())
        .set(NOTIFICATION.DISPATCHED_AT, now)
        .where(NOTIFICATION.ID.eq(notificationId))
        .and(NOTIFICATION.STATUS.eq(NotificationStatus.PENDING.name()))
        .execute();
  }

  // Feed

  @Override
  public List<InAppFeedItem> findInAppFeed(
      UUID orgId, UUID userId, boolean unreadOnly, int offset, int limit) {
    return dsl.select(NOTIFICATION.fields())
        .select(
            NOTIFICATION_DELIVERY_IN_APP.READ_AT,
            NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT,
            NOTIFICATION_DELIVERY_IN_APP.LINK_TARGET)
        .from(NOTIFICATION)
        .join(NOTIFICATION_DELIVERY)
        .on(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(NOTIFICATION.ID))
        .and(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.IN_APP.dbValue()))
        .join(NOTIFICATION_DELIVERY_IN_APP)
        .on(NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.eq(NOTIFICATION_DELIVERY.ID))
        .where(feedCondition(orgId, userId, unreadOnly))
        .orderBy(NOTIFICATION.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch(this::toFeedItem);
  }

  @Override
  public long countInAppFeed(UUID orgId, UUID userId, boolean unreadOnly) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(NOTIFICATION)
            .join(NOTIFICATION_DELIVERY)
            .on(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(NOTIFICATION.ID))
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.IN_APP.dbValue()))
            .join(NOTIFICATION_DELIVERY_IN_APP)
            .on(NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.eq(NOTIFICATION_DELIVERY.ID))
            .where(feedCondition(orgId, userId, unreadOnly)));
  }

  @Override
  public int markInAppRead(UUID orgId, UUID userId, UUID notificationId, OffsetDateTime now) {
    // COALESCE keeps the first-read timestamp stable, so re-marking is an idempotent no-op that
    // still matches the row: 0 rows updated therefore means strictly "not owned / not found" → 404.
    return dsl.update(NOTIFICATION_DELIVERY_IN_APP)
        .set(
            NOTIFICATION_DELIVERY_IN_APP.READ_AT,
            DSL.coalesce(NOTIFICATION_DELIVERY_IN_APP.READ_AT, now))
        .where(
            NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.in(
                ownInAppDeliveryIds(orgId, userId, notificationId)))
        .execute();
  }

  @Override
  public int markInAppDismissed(UUID orgId, UUID userId, UUID notificationId, OffsetDateTime now) {
    return dsl.update(NOTIFICATION_DELIVERY_IN_APP)
        .set(
            NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT,
            DSL.coalesce(NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT, now))
        .where(
            NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.in(
                ownInAppDeliveryIds(orgId, userId, notificationId)))
        .execute();
  }

  // Customer feed (the portal plane — slice P5)

  @Override
  public List<InAppFeedItem> findCustomerInAppFeed(
      UUID orgId, UUID customerId, boolean unreadOnly, int offset, int limit) {
    return dsl.select(NOTIFICATION.fields())
        .select(
            NOTIFICATION_DELIVERY_IN_APP.READ_AT,
            NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT,
            NOTIFICATION_DELIVERY_IN_APP.LINK_TARGET)
        .from(NOTIFICATION)
        .join(NOTIFICATION_DELIVERY)
        .on(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(NOTIFICATION.ID))
        .and(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.IN_APP.dbValue()))
        .join(NOTIFICATION_DELIVERY_IN_APP)
        .on(NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.eq(NOTIFICATION_DELIVERY.ID))
        .where(customerFeedCondition(orgId, customerId, unreadOnly))
        .orderBy(NOTIFICATION.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch(this::toFeedItem);
  }

  @Override
  public long countCustomerInAppFeed(UUID orgId, UUID customerId, boolean unreadOnly) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(NOTIFICATION)
            .join(NOTIFICATION_DELIVERY)
            .on(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(NOTIFICATION.ID))
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.IN_APP.dbValue()))
            .join(NOTIFICATION_DELIVERY_IN_APP)
            .on(NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.eq(NOTIFICATION_DELIVERY.ID))
            .where(customerFeedCondition(orgId, customerId, unreadOnly)));
  }

  @Override
  public int markCustomerInAppRead(
      UUID orgId, UUID customerId, UUID notificationId, OffsetDateTime now) {
    // Same COALESCE idempotency as the staff feed: 0 rows = strictly not owned / not found → 404.
    return dsl.update(NOTIFICATION_DELIVERY_IN_APP)
        .set(
            NOTIFICATION_DELIVERY_IN_APP.READ_AT,
            DSL.coalesce(NOTIFICATION_DELIVERY_IN_APP.READ_AT, now))
        .where(
            NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.in(
                ownCustomerInAppDeliveryIds(orgId, customerId, notificationId)))
        .execute();
  }

  @Override
  public int markCustomerInAppDismissed(
      UUID orgId, UUID customerId, UUID notificationId, OffsetDateTime now) {
    return dsl.update(NOTIFICATION_DELIVERY_IN_APP)
        .set(
            NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT,
            DSL.coalesce(NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT, now))
        .where(
            NOTIFICATION_DELIVERY_IN_APP.DELIVERY_ID.in(
                ownCustomerInAppDeliveryIds(orgId, customerId, notificationId)))
        .execute();
  }

  // helpers

  /**
   * In-app delivery ids for one notification that actually belong to this org+user (own-only gate).
   */
  private org.jooq.Select<org.jooq.Record1<UUID>> ownInAppDeliveryIds(
      UUID orgId, UUID userId, UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.ID)
        .from(NOTIFICATION_DELIVERY)
        .join(NOTIFICATION)
        .on(NOTIFICATION.ID.eq(NOTIFICATION_DELIVERY.NOTIFICATION_ID))
        .where(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.IN_APP.dbValue()))
        .and(NOTIFICATION.ID.eq(notificationId))
        .and(NOTIFICATION.ORG_ID.eq(orgId))
        .and(NOTIFICATION.RECIPIENT_USER_ID.eq(userId));
  }

  private Condition feedCondition(UUID orgId, UUID userId, boolean unreadOnly) {
    Condition c =
        NOTIFICATION
            .ORG_ID
            .eq(orgId)
            .and(NOTIFICATION.RECIPIENT_TYPE.eq(RecipientType.USER.name()))
            .and(NOTIFICATION.RECIPIENT_USER_ID.eq(userId))
            .and(NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT.isNull());
    if (unreadOnly) {
      c = c.and(NOTIFICATION_DELIVERY_IN_APP.READ_AT.isNull());
    }
    return c;
  }

  /** The customer twin of {@link #feedCondition} — keyed on {@code recipient_customer_id}. */
  private Condition customerFeedCondition(UUID orgId, UUID customerId, boolean unreadOnly) {
    Condition c =
        NOTIFICATION
            .ORG_ID
            .eq(orgId)
            .and(NOTIFICATION.RECIPIENT_TYPE.eq(RecipientType.CUSTOMER.name()))
            .and(NOTIFICATION.RECIPIENT_CUSTOMER_ID.eq(customerId))
            .and(NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT.isNull());
    if (unreadOnly) {
      c = c.and(NOTIFICATION_DELIVERY_IN_APP.READ_AT.isNull());
    }
    return c;
  }

  /** The customer twin of {@link #ownInAppDeliveryIds} (own-only gate for read/dismiss). */
  private org.jooq.Select<org.jooq.Record1<UUID>> ownCustomerInAppDeliveryIds(
      UUID orgId, UUID customerId, UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.ID)
        .from(NOTIFICATION_DELIVERY)
        .join(NOTIFICATION)
        .on(NOTIFICATION.ID.eq(NOTIFICATION_DELIVERY.NOTIFICATION_ID))
        .where(NOTIFICATION_DELIVERY.CHANNEL.eq(NotificationChannel.IN_APP.dbValue()))
        .and(NOTIFICATION.ID.eq(notificationId))
        .and(NOTIFICATION.ORG_ID.eq(orgId))
        .and(NOTIFICATION.RECIPIENT_TYPE.eq(RecipientType.CUSTOMER.name()))
        .and(NOTIFICATION.RECIPIENT_CUSTOMER_ID.eq(customerId));
  }

  private InAppFeedItem toFeedItem(Record r) {
    Notification n = toNotification(r.into(NOTIFICATION));
    return new InAppFeedItem(
        n,
        r.get(NOTIFICATION_DELIVERY_IN_APP.READ_AT),
        r.get(NOTIFICATION_DELIVERY_IN_APP.DISMISSED_AT),
        r.get(NOTIFICATION_DELIVERY_IN_APP.LINK_TARGET));
  }

  private Notification toNotification(NotificationRecord r) {
    return new Notification(
        r.getId(),
        r.getOrgId(),
        RecipientType.valueOf(r.getRecipientType()),
        r.getRecipientUserId(),
        r.getRecipientCustomerId(),
        r.getType(),
        r.getTitle(),
        r.getBody(),
        r.getSourceType(),
        r.getSourceId(),
        NotificationStatus.valueOf(r.getStatus()),
        r.getPayload() == null ? null : r.getPayload().data(),
        r.getCreatedAt(),
        r.getDispatchedAt());
  }

  private NotificationDelivery toDelivery(NotificationDeliveryRecord r) {
    return new NotificationDelivery(
        r.getId(),
        r.getNotificationId(),
        NotificationChannel.fromDbValue(r.getChannel()),
        DeliveryStatus.valueOf(r.getStatus()),
        r.getAttempts(),
        r.getLastError(),
        r.getSentAt(),
        r.getDeliveredAt(),
        r.getFailedAt(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
