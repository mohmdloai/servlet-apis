package com.loai.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.DeliveryStatus;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationDelivery;
import com.loai.inventory.domain.model.NotificationPreference;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationStatus;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.RecipientType;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.NotificationPreferenceRepository;
import com.loai.inventory.domain.repository.NotificationPreferenceRepositoryFactory;
import com.loai.inventory.domain.repository.NotificationRepository;
import com.loai.inventory.domain.repository.NotificationRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
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
 * <p>Two channels are wired: a {@code USER} recipient gets an {@code in_app} delivery; a {@code
 * CUSTOMER} recipient gets an {@code in_app} delivery (the portal feed — slice P5) <em>and</em> an
 * {@code email} delivery (the offline reach). Email is transmitted by {@link #dispatchPendingEmail}
 * through an {@link EmailSender} <em>after</em> the business txn commits (never inside it).
 */
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  /** "Org staff" for fan-out: everyone who acts on the org (VIEWER is read-only, excluded). */
  private static final Set<OrgRole> STAFF_ROLES =
      Set.of(OrgRole.STAFF, OrgRole.MANAGER, OrgRole.OWNER);

  /** Default retry budget for an email delivery before it is marked terminally FAILED. */
  public static final int DEFAULT_EMAIL_MAX_ATTEMPTS = 5;

  private final DSLContext rootDsl;
  private final NotificationRepositoryFactory notificationRepoFactory;
  private final UserRepositoryFactory userRepoFactory;
  private final CustomerRepositoryFactory customerRepoFactory;
  private final NotificationPreferenceRepositoryFactory preferenceRepoFactory;
  private final EmailSender emailSender;
  private final MagicLinkService magicLinkService;
  private final int emailMaxAttempts;
  private final ObjectMapper payloadMapper = new ObjectMapper();

  public NotificationService(
      DSLContext rootDsl,
      NotificationRepositoryFactory notificationRepoFactory,
      UserRepositoryFactory userRepoFactory,
      CustomerRepositoryFactory customerRepoFactory,
      NotificationPreferenceRepositoryFactory preferenceRepoFactory,
      EmailSender emailSender,
      MagicLinkService magicLinkService,
      int emailMaxAttempts) {
    this.rootDsl = rootDsl;
    this.notificationRepoFactory = notificationRepoFactory;
    this.userRepoFactory = userRepoFactory;
    this.customerRepoFactory = customerRepoFactory;
    this.preferenceRepoFactory = preferenceRepoFactory;
    this.emailSender = emailSender;
    this.magicLinkService = magicLinkService;
    this.emailMaxAttempts = emailMaxAttempts > 0 ? emailMaxAttempts : DEFAULT_EMAIL_MAX_ATTEMPTS;
  }

  /** A staff-preference upsert input from the PUT endpoint. */
  public record PreferenceInput(String type, NotificationChannel channel, boolean enabled) {}

  /**
   * Result of one sweeper tick. {@code sent}/{@code retried}/{@code failed}/{@code skipped} are
   * disjoint and sum to {@code picked}: {@code retried} stayed PENDING (a transient fault, will be
   * re-attempted), {@code failed} reached terminal FAILED <em>this</em> tick, and {@code skipped}
   * was already consumed by another tick (or its subtype row was missing). Keeping these apart
   * stops a re-attempted delivery from masquerading as a failure on every sweep.
   */
  public record DeliverySummary(int picked, int sent, int retried, int failed, int skipped) {}

  /** What one delivery's dispatch did on a tick — the disjoint tally categories above. */
  private enum DeliveryOutcome {
    SENT,
    RETRIED,
    FAILED,
    SKIPPED
  }

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
    OffsetDateTime now = now();

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

    int created = 0;
    for (NotificationChannel channel : channelsFor(recipient)) {
      // Opt-out resolution: a preference row can suppress this channel. Absence = enabled.
      if (!isChannelEnabled(txDsl, orgId, recipient, type.name(), channel)) {
        continue;
      }
      NotificationDelivery d = new NotificationDelivery();
      d.setNotificationId(saved.getId());
      d.setChannel(channel);
      d.setStatus(DeliveryStatus.PENDING);
      d.setAttempts(0);
      NotificationDelivery savedDelivery = repo.insertDelivery(d);
      switch (channel) {
        case IN_APP -> repo.insertInAppDelivery(savedDelivery.getId(), linkTarget);
        case EMAIL -> {
          // Freeze the send target + rendered content now; the sweeper transmits it later. Every
          // customer email carries a one-click unsubscribe link (minted in this same txn).
          String toAddress = resolveCustomerEmail(txDsl, orgId, recipient.customerId());
          String unsubscribeUrl =
              magicLinkService.issueUnsubscribeLink(txDsl, orgId, recipient.customerId(), now);
          String html =
              NotificationTemplates.emailHtml(
                  rendered.body(), linkTarget, rendered.ctaLabel(), unsubscribeUrl);
          repo.insertEmailDelivery(savedDelivery.getId(), toAddress, rendered.title(), html);
        }
      }
      created++;
    }
    // Fully suppressed by preferences → no delivery will ever run; finalize now so it doesn't hang
    // PENDING. (The row is kept as an audit trail that we would have notified.)
    if (created == 0) {
      finalizeIfTerminal(repo, saved.getId(), now);
    }
    return saved;
  }

  /**
   * The effective enabled/disabled for {@code (recipient, type, channel)}: an explicit preference
   * wins, else the opt-out default (enabled). USER subjects key on the recipient's user id,
   * CUSTOMER on the customer id.
   */
  boolean isChannelEnabled(
      DSLContext txDsl,
      UUID orgId,
      NotificationRecipient recipient,
      String type,
      NotificationChannel channel) {
    NotificationPreferenceRepository prefs = preferenceRepoFactory.create(txDsl);
    RecipientType subjectType = recipient.type();
    UUID subjectId =
        subjectType == RecipientType.USER ? recipient.userId() : recipient.customerId();
    return prefs.resolveEnabled(orgId, subjectType, subjectId, type, channel).orElse(true);
  }

  /**
   * USER → in_app; CUSTOMER → in_app + email (slice P5): the portal gives customers a logged-in
   * feed, so the durable in-app row is the reliable channel and email stays the offline reach.
   * Either leg can still be suppressed per-preference.
   */
  private List<NotificationChannel> channelsFor(NotificationRecipient recipient) {
    return switch (recipient.type()) {
      case USER -> List.of(NotificationChannel.IN_APP);
      case CUSTOMER -> List.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL);
    };
  }

  /** The current email for a customer recipient — required for an email delivery. */
  private String resolveCustomerEmail(DSLContext txDsl, UUID orgId, UUID customerId) {
    CustomerRepository customers = customerRepoFactory.create(txDsl);
    Customer c =
        customers
            .findById(orgId, customerId)
            .orElseThrow(() -> new NotFoundException("Customer", customerId));
    if (c.getEmail() == null || c.getEmail().isBlank()) {
      throw new IllegalStateException("customer " + customerId + " has no email for notification");
    }
    return c.getEmail();
  }

  // ── Preferences (opt-out) ───────────────────────────────────────────────────

  /** A staff user's own preference rows (for the read endpoint). */
  public List<NotificationPreference> getUserPreferences(UUID orgId, UUID userId) {
    return preferenceRepoFactory.create(rootDsl).findByUser(orgId, userId);
  }

  /**
   * Upsert (merge) the given preferences for a staff user, then return the resulting set. Each
   * input is validated: {@code type} must be a known {@link NotificationType} name or {@link
   * NotificationPreference#ALL_TYPES}, and {@code channel} must be present.
   */
  public List<NotificationPreference> setUserPreferences(
      UUID orgId, UUID userId, List<PreferenceInput> inputs) {
    for (PreferenceInput in : inputs) {
      if (in == null || in.channel() == null) {
        throw new ValidationException("each preference needs a channel");
      }
      validatePreferenceType(in.type());
    }
    rootDsl.transaction(
        cfg -> {
          NotificationPreferenceRepository prefs = preferenceRepoFactory.create(DSL.using(cfg));
          for (PreferenceInput in : inputs) {
            prefs.upsertUser(orgId, userId, in.type(), in.channel(), in.enabled());
          }
        });
    return getUserPreferences(orgId, userId);
  }

  /** A customer's own preference rows (the portal read endpoint — slice P5). */
  public List<NotificationPreference> getCustomerPreferences(UUID orgId, UUID customerId) {
    return preferenceRepoFactory.create(rootDsl).findByCustomer(orgId, customerId);
  }

  /**
   * Upsert (merge) the given preferences for a customer, then return the resulting set — the
   * portal-plane twin of {@link #setUserPreferences}, writing the same rows the one-click
   * unsubscribe link writes.
   */
  public List<NotificationPreference> setCustomerPreferences(
      UUID orgId, UUID customerId, List<PreferenceInput> inputs) {
    for (PreferenceInput in : inputs) {
      if (in == null || in.channel() == null) {
        throw new ValidationException("each preference needs a channel");
      }
      validatePreferenceType(in.type());
    }
    rootDsl.transaction(
        cfg -> {
          NotificationPreferenceRepository prefs = preferenceRepoFactory.create(DSL.using(cfg));
          for (PreferenceInput in : inputs) {
            prefs.upsertCustomer(orgId, customerId, in.type(), in.channel(), in.enabled());
          }
        });
    return getCustomerPreferences(orgId, customerId);
  }

  /** Turn a customer's email off for the org (the one-click unsubscribe target). Idempotent. */
  public void unsubscribeCustomerEmail(UUID orgId, UUID customerId) {
    rootDsl.transaction(
        cfg ->
            preferenceRepoFactory
                .create(DSL.using(cfg))
                .upsertCustomer(
                    orgId,
                    customerId,
                    NotificationPreference.ALL_TYPES,
                    NotificationChannel.EMAIL,
                    false));
  }

  private static void validatePreferenceType(String type) {
    if (type == null || type.isBlank()) {
      throw new ValidationException("preference type is required");
    }
    if (NotificationPreference.ALL_TYPES.equals(type)) {
      return;
    }
    try {
      NotificationType.valueOf(type);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("unknown notification type: " + type);
    }
  }

  // ── Worker: drain pending in-app deliveries ─────────────────────────────────

  public DeliverySummary dispatchPendingInApp(int batchLimit) {
    // Read candidates in autocommit (no long-held txn), then dispatch each in its own transaction.
    NotificationRepository reader = notificationRepoFactory.create(rootDsl);
    List<UUID> ids = reader.findPendingDeliveryIds(NotificationChannel.IN_APP, batchLimit);
    int sent = 0;
    int failed = 0;
    int skipped = 0;
    for (UUID id : ids) {
      try {
        if (dispatchOneInApp(id) == DeliveryOutcome.SENT) {
          sent++;
        } else {
          skipped++; // already consumed by another tick, or gone
        }
      } catch (RuntimeException e) {
        failed++;
        log.warn("in-app delivery {} failed to dispatch", id, e);
      }
    }
    return new DeliverySummary(ids.size(), sent, /* retried= */ 0, failed, skipped);
  }

  private DeliveryOutcome dispatchOneInApp(UUID deliveryId) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          NotificationDelivery d = repo.findDeliveryById(deliveryId).orElse(null);
          if (d == null || d.getStatus() != DeliveryStatus.PENDING) {
            return DeliveryOutcome.SKIPPED; // consumed by another tick, or gone
          }
          OffsetDateTime now = now();
          // in-app: the row IS the delivery — nothing to hand to a provider. Mark SENT.
          repo.markDeliverySent(deliveryId, now);
          finalizeIfTerminal(repo, d.getNotificationId(), now);
          return DeliveryOutcome.SENT;
        });
  }

  // ── Worker: drain pending email deliveries ──────────────────────────────────

  /**
   * Drain PENDING email deliveries: read candidate ids in autocommit, then dispatch each in its own
   * transaction (poison-pill isolation). The SMTP call runs inside the per-delivery txn, under the
   * row's {@code FOR UPDATE} lock, so concurrent ticks can't double-send. At-least-once: a crash
   * between send and commit re-sends next tick.
   */
  public DeliverySummary dispatchPendingEmail(int batchLimit) {
    NotificationRepository reader = notificationRepoFactory.create(rootDsl);
    List<UUID> ids = reader.findPendingDeliveryIds(NotificationChannel.EMAIL, batchLimit);
    int sent = 0;
    int retried = 0;
    int failed = 0;
    int skipped = 0;
    for (UUID id : ids) {
      DeliveryOutcome outcome;
      try {
        outcome = dispatchOneEmail(id);
      } catch (RuntimeException e) {
        // An infra fault (DB) outside the provider call — the txn rolled back, so the row is
        // untouched and will be retried next tick. Count it as retried, not failed.
        outcome = DeliveryOutcome.RETRIED;
        log.warn("email delivery {} errored during dispatch (will retry)", id, e);
      }
      switch (outcome) {
        case SENT -> sent++;
        case RETRIED -> retried++;
        case FAILED -> failed++;
        case SKIPPED -> skipped++;
      }
    }
    return new DeliverySummary(ids.size(), sent, retried, failed, skipped);
  }

  /** Dispatch one email delivery and report what happened (see {@link DeliveryOutcome}). */
  private DeliveryOutcome dispatchOneEmail(UUID deliveryId) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          NotificationDelivery d = repo.findDeliveryById(deliveryId).orElse(null);
          if (d == null || d.getStatus() != DeliveryStatus.PENDING) {
            return DeliveryOutcome.SKIPPED; // consumed by another tick, or gone
          }
          OffsetDateTime now = now();
          NotificationRepository.EmailDeliveryContent content =
              repo.findEmailDeliveryContent(deliveryId).orElse(null);
          if (content == null) {
            // Missing subtype row is a producer bug, not transient — fail terminally.
            repo.markDeliveryFailed(deliveryId, "missing email subtype row", now);
            finalizeIfTerminal(repo, d.getNotificationId(), now);
            return DeliveryOutcome.FAILED;
          }
          try {
            emailSender.send(
                new EmailMessage(content.toAddress(), content.subject(), content.renderedHtml()));
          } catch (RuntimeException e) {
            // Any provider fault — EmailException OR an out-of-contract runtime error from the
            // sender. Both must advance the attempt counter; otherwise a sender that throws e.g. an
            // NPE would leave the row PENDING with attempts unchanged and be re-sent forever.
            int attempted = d.getAttempts() + 1;
            if (attempted >= emailMaxAttempts) {
              repo.markDeliveryFailed(deliveryId, truncateError(e.getMessage()), now);
              finalizeIfTerminal(repo, d.getNotificationId(), now);
              return DeliveryOutcome.FAILED;
            }
            // Leave PENDING so the next sweep retries.
            repo.markDeliveryRetry(deliveryId, truncateError(e.getMessage()), now);
            return DeliveryOutcome.RETRIED;
          }
          repo.markDeliverySent(deliveryId, now);
          finalizeIfTerminal(repo, d.getNotificationId(), now);
          return DeliveryOutcome.SENT;
        });
  }

  /**
   * Once every delivery of a notification is terminal (SENT/DELIVERED/FAILED), flip it DISPATCHED.
   */
  private void finalizeIfTerminal(
      NotificationRepository repo, UUID notificationId, OffsetDateTime now) {
    if (repo.allDeliveriesTerminal(notificationId)) {
      repo.markNotificationDispatched(notificationId, now);
    }
  }

  private static String truncateError(String message) {
    if (message == null) {
      return null;
    }
    return message.length() <= 500 ? message : message.substring(0, 500);
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

  // Customer feed (own-only, the portal plane — slice P5)

  public List<InAppFeedItem> getCustomerFeed(
      UUID orgId, UUID customerId, boolean unreadOnly, int page, int size) {
    int offset = Pagination.offset(page, size);
    return notificationRepoFactory
        .create(rootDsl)
        .findCustomerInAppFeed(orgId, customerId, unreadOnly, offset, size);
  }

  public long countCustomerFeed(UUID orgId, UUID customerId, boolean unreadOnly) {
    return notificationRepoFactory
        .create(rootDsl)
        .countCustomerInAppFeed(orgId, customerId, unreadOnly);
  }

  public void markCustomerRead(UUID orgId, UUID customerId, UUID notificationId) {
    mutateOwnCustomer(orgId, customerId, notificationId, /* dismiss= */ false);
  }

  public void markCustomerDismissed(UUID orgId, UUID customerId, UUID notificationId) {
    mutateOwnCustomer(orgId, customerId, notificationId, /* dismiss= */ true);
  }

  /** A foreign/unknown id is the same opaque 404 as the staff feed — never an ownership oracle. */
  private void mutateOwnCustomer(
      UUID orgId, UUID customerId, UUID notificationId, boolean dismiss) {
    rootDsl.transaction(
        cfg -> {
          NotificationRepository repo = notificationRepoFactory.create(DSL.using(cfg));
          OffsetDateTime now = now();
          int updated =
              dismiss
                  ? repo.markCustomerInAppDismissed(orgId, customerId, notificationId, now)
                  : repo.markCustomerInAppRead(orgId, customerId, notificationId, now);
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
