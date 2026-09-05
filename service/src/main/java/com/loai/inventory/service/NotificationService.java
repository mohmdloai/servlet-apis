package com.loai.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Locales;
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
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import com.loai.inventory.domain.model.PushSubscription;
import com.loai.inventory.domain.model.RecipientType;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.NotificationPreferenceRepository;
import com.loai.inventory.domain.repository.NotificationPreferenceRepositoryFactory;
import com.loai.inventory.domain.repository.NotificationRepository;
import com.loai.inventory.domain.repository.NotificationRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.OrgWhatsAppConfigRepositoryFactory;
import com.loai.inventory.domain.repository.PushSubscriptionRepository;
import com.loai.inventory.domain.repository.PushSubscriptionRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.push.LoggingWebPushSender;
import com.loai.inventory.service.push.PushTarget;
import com.loai.inventory.service.push.WebPushException;
import com.loai.inventory.service.push.WebPushSender;
import com.loai.inventory.service.whatsapp.CloudApiWhatsAppSender;
import com.loai.inventory.service.whatsapp.WhatsAppMessage;
import com.loai.inventory.service.whatsapp.WhatsAppSender;
import com.loai.inventory.service.whatsapp.WhatsAppTemplates;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Channels: a {@code USER} recipient gets an {@code in_app} delivery, plus one {@code push}
 * delivery <b>per live device</b> (V96, {@code stories/web_push_channel.md}); a {@code CUSTOMER}
 * recipient gets an {@code in_app} delivery (the portal feed — slice P5) <em>and</em> an {@code
 * email} delivery (the offline reach), plus WhatsApp when the org has a live WABA (V82). Every
 * outbound channel is transmitted by its {@code dispatchPending*} walk through its sender
 * <em>after</em> the business txn commits (never inside it).
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
  private final OrgRepositoryFactory orgRepoFactory;
  private final OrgWhatsAppConfigRepositoryFactory whatsAppConfigRepoFactory;
  private final EmailSender emailSender;
  private final MagicLinkService magicLinkService;
  private final WhatsAppSender whatsAppSender;
  private final PushSubscriptionRepositoryFactory pushSubscriptionRepoFactory;
  private final WebPushSender webPushSender;
  private final int emailMaxAttempts;
  private final int pushMaxAttempts;
  private final ObjectMapper payloadMapper = new ObjectMapper();

  public NotificationService(
      DSLContext rootDsl,
      NotificationRepositoryFactory notificationRepoFactory,
      UserRepositoryFactory userRepoFactory,
      CustomerRepositoryFactory customerRepoFactory,
      NotificationPreferenceRepositoryFactory preferenceRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      OrgWhatsAppConfigRepositoryFactory whatsAppConfigRepoFactory,
      EmailSender emailSender,
      MagicLinkService magicLinkService,
      WhatsAppSender whatsAppSender,
      int emailMaxAttempts) {
    // Push not wired: no subscription repository means no device is ever a target, and the logging
    // sender is never reached. The shape the pre-V96 ITs construct.
    this(
        rootDsl,
        notificationRepoFactory,
        userRepoFactory,
        customerRepoFactory,
        preferenceRepoFactory,
        orgRepoFactory,
        whatsAppConfigRepoFactory,
        emailSender,
        magicLinkService,
        whatsAppSender,
        emailMaxAttempts,
        null,
        new LoggingWebPushSender(),
        emailMaxAttempts);
  }

  /**
   * The full wiring, with the Web Push leg (V96). {@code pushMaxAttempts} defaults to the email
   * budget when not positive — the three outbound channels share one budget unless the operator
   * says otherwise.
   */
  public NotificationService(
      DSLContext rootDsl,
      NotificationRepositoryFactory notificationRepoFactory,
      UserRepositoryFactory userRepoFactory,
      CustomerRepositoryFactory customerRepoFactory,
      NotificationPreferenceRepositoryFactory preferenceRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      OrgWhatsAppConfigRepositoryFactory whatsAppConfigRepoFactory,
      EmailSender emailSender,
      MagicLinkService magicLinkService,
      WhatsAppSender whatsAppSender,
      int emailMaxAttempts,
      PushSubscriptionRepositoryFactory pushSubscriptionRepoFactory,
      WebPushSender webPushSender,
      int pushMaxAttempts) {
    this.rootDsl = rootDsl;
    this.notificationRepoFactory = notificationRepoFactory;
    this.userRepoFactory = userRepoFactory;
    this.customerRepoFactory = customerRepoFactory;
    this.preferenceRepoFactory = preferenceRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.whatsAppConfigRepoFactory = whatsAppConfigRepoFactory;
    this.emailSender = emailSender;
    this.magicLinkService = magicLinkService;
    this.whatsAppSender = whatsAppSender;
    this.pushSubscriptionRepoFactory = pushSubscriptionRepoFactory;
    this.webPushSender = webPushSender == null ? new LoggingWebPushSender() : webPushSender;
    this.emailMaxAttempts = emailMaxAttempts > 0 ? emailMaxAttempts : DEFAULT_EMAIL_MAX_ATTEMPTS;
    this.pushMaxAttempts = pushMaxAttempts > 0 ? pushMaxAttempts : this.emailMaxAttempts;
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

  // Producer (runs inside the caller's business transaction)

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
    String locale = resolveLocale(txDsl, orgId, recipient);
    NotificationTemplates.Rendered rendered = NotificationTemplates.render(type, payload, locale);
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
    for (NotificationChannel channel :
        channelsFor(txDsl, orgId, recipient, type, payload, locale)) {
      // Opt-out resolution: a preference row can suppress this channel. Absence = enabled.
      if (!isChannelEnabled(txDsl, orgId, recipient, type.name(), channel)) {
        continue;
      }
      if (channel == NotificationChannel.PUSH) {
        // One delivery PER DEVICE, not per channel — see producePushLegs. Zero targets here (a
        // device pruned between channelsFor and now) simply produces nothing.
        created +=
            producePushLegs(txDsl, repo, saved, recipient, type, rendered, sourceType, sourceId);
        continue;
      }
      // An email channel with nowhere to send is resolved BEFORE the delivery row is written, so a
      // customer without an address costs nothing and leaves nothing half-created.
      String toAddress = null;
      if (channel == NotificationChannel.EMAIL) {
        Optional<String> resolved = resolveCustomerEmail(txDsl, orgId, recipient.customerId());
        if (resolved.isEmpty()) {
          log.warn(
              "Skipping email leg of {} for customer {} in org {} — no address on the record",
              type,
              recipient.customerId(),
              orgId);
          continue;
        }
        toAddress = resolved.get();
      }
      NotificationDelivery d = new NotificationDelivery();
      d.setNotificationId(saved.getId());
      d.setChannel(channel);
      d.setStatus(DeliveryStatus.PENDING);
      d.setAttempts(0);
      NotificationDelivery savedDelivery = repo.insertDelivery(d);
      switch (channel) {
        case IN_APP -> repo.insertInAppDelivery(savedDelivery.getId(), linkTarget);
        case WHATSAPP -> {
          // Freeze the template INVOCATION now, exactly as the email leg freezes its rendered
          // body: the sweeper transmits later, and what was sent must be reconstructable from the
          // row rather than re-derived from a payload that may since have changed meaning.
          WhatsAppTemplates.Spec spec =
              whatsAppSpecFor(txDsl, orgId, recipient, type, payload, locale);
          repo.insertWhatsAppDelivery(
              savedDelivery.getId(),
              customerPhoneE164(txDsl, orgId, recipient.customerId()),
              spec.name(),
              spec.language(),
              serializeParams(spec.params()));
        }
        case EMAIL -> {
          // Freeze the send target + rendered content now; the sweeper transmits it later. Every
          // customer email carries a one-click unsubscribe link (minted in this same txn).
          String unsubscribeUrl =
              magicLinkService.issueUnsubscribeLink(txDsl, orgId, recipient.customerId(), now);
          String html =
              NotificationTemplates.emailHtml(
                  rendered.body(), linkTarget, rendered.ctaLabel(), unsubscribeUrl, locale);
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
   * USER → in_app; CUSTOMER → in_app + email, plus WhatsApp when the org and the customer both
   * support it (slice B). The portal gives customers a logged-in feed, so the durable in-app row is
   * the reliable channel; email is the offline reach; WhatsApp is the one people actually read.
   * Every leg can still be suppressed per-preference.
   *
   * <p><b>This method is the whole channel seam.</b> It used to be a constant two-line switch;
   * WhatsApp is the first channel whose availability is a per-org, per-customer, per-type question,
   * and the answer is computed here so nothing downstream — preferences, the sweeper, the
   * fully-suppressed finalization path — has to know that.
   *
   * <p><b>WhatsApp is additive, not a replacement for email.</b> A shopper with both gets both.
   * That is the conservative default: preferences already let either side be turned off, nobody
   * silently loses a message, and the opposite policy (WhatsApp wins, email as fallback) is a
   * one-line change right here if the duplication turns out to annoy people more than a missed
   * message would.
   *
   * <p>Three conditions, all cheap, all failing to <em>absence</em> rather than error — the D3
   * precedent that a channel with nowhere to go is suppressed, never a failed business event.
   */
  private List<NotificationChannel> channelsFor(
      DSLContext txDsl,
      UUID orgId,
      NotificationRecipient recipient,
      NotificationType type,
      Map<String, Object> payload,
      String locale) {
    if (recipient.type() == RecipientType.USER) {
      // Push accelerates; the feed row and the bell's poll still own delivery. The leg exists only
      // when the user has a live subscription — resolved to absence, never to error, like WhatsApp.
      if (pushTargetsFor(txDsl, recipient.userId()).isEmpty()) {
        return List.of(NotificationChannel.IN_APP);
      }
      return List.of(NotificationChannel.IN_APP, NotificationChannel.PUSH);
    }
    List<NotificationChannel> channels =
        new ArrayList<>(List.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL));
    if (whatsAppSpecFor(txDsl, orgId, recipient, type, payload, locale) != null) {
      channels.add(NotificationChannel.WHATSAPP);
    }
    return channels;
  }

  /**
   * The WhatsApp invocation for this notification, or null when the channel does not apply.
   *
   * <p>Null for any of: the org has no ACTIVE {@code org_whatsapp_config}; the customer has no
   * {@code phone_e164} (V79 — an unparseable number is exactly this, unreachable); or the event has
   * no approved utility template ({@code WhatsAppTemplates.specFor} returns null for the two types
   * Meta would classify as marketing). Computed twice per notification — once to decide the channel
   * list, once to write the row — which is a couple of primary-key reads at human cadence, and much
   * easier to follow than threading a half-resolved state between the two.
   */
  private WhatsAppTemplates.Spec whatsAppSpecFor(
      DSLContext txDsl,
      UUID orgId,
      NotificationRecipient recipient,
      NotificationType type,
      Map<String, Object> payload,
      String locale) {
    try {
      WhatsAppTemplates.Spec spec = WhatsAppTemplates.specFor(type, payload, locale);
      if (spec == null) {
        return null;
      }
      boolean orgConnected =
          whatsAppConfigRepoFactory
              .create(txDsl)
              .findByOrgId(orgId)
              .map(OrgWhatsAppConfig::isActive)
              .orElse(false);
      if (!orgConnected) {
        return null;
      }
      return customerRepoFactory
              .create(txDsl)
              .findById(orgId, recipient.customerId())
              .map(Customer::getPhoneE164)
              .filter(n -> n != null && !n.isBlank())
              .isPresent()
          ? spec
          : null;
    } catch (RuntimeException e) {
      // Must not throw: notify() runs inside the caller's business transaction, so a failure to
      // answer "is WhatsApp available?" cannot be allowed to roll back an order.
      log.warn("Could not resolve the WhatsApp channel for org {} — skipping it", orgId, e);
      return null;
    }
  }

  /**
   * The user's live push subscriptions — the devices a {@code push} leg fans out to (V96).
   *
   * <p>Live = the user is active and the row's {@code token_version_at_subscribe} still equals
   * {@code app_user.token_version}, so every existing sign-out-everywhere path (logout-all,
   * password change, de-privilege, platform disable) silences push with no new call site. Empty
   * when push is not wired at all (no repository factory), when the user has no device, or when the
   * read throws — the {@link #whatsAppSpecFor} rule, for the same reason: this runs inside the
   * caller's business transaction and must never roll it back. Computed twice per notification
   * (once to decide the channel list, once to write the rows), a couple of indexed reads at human
   * cadence.
   */
  List<PushSubscription> pushTargetsFor(DSLContext txDsl, UUID userId) {
    if (pushSubscriptionRepoFactory == null || userId == null) {
      return List.of();
    }
    try {
      return pushSubscriptionRepoFactory.create(txDsl).findLiveByUser(userId);
    } catch (RuntimeException e) {
      log.warn("Could not resolve push subscriptions for user {} — skipping push", userId, e);
      return List.of();
    }
  }

  /**
   * Write one {@code push} delivery + subtype row per live device, the payload frozen now. Returns
   * how many were written. A device subscribed a second later gets the next event, not this one.
   */
  private int producePushLegs(
      DSLContext txDsl,
      NotificationRepository repo,
      Notification saved,
      NotificationRecipient recipient,
      NotificationType type,
      NotificationTemplates.Rendered rendered,
      String sourceType,
      UUID sourceId) {
    List<PushSubscription> targets = pushTargetsFor(txDsl, recipient.userId());
    if (targets.isEmpty()) {
      return 0;
    }
    String payloadJson =
        pushPayloadJson(saved.getId(), saved.getOrgId(), type, rendered, sourceType, sourceId);
    int written = 0;
    for (PushSubscription sub : targets) {
      NotificationDelivery d = new NotificationDelivery();
      d.setNotificationId(saved.getId());
      d.setChannel(NotificationChannel.PUSH);
      d.setStatus(DeliveryStatus.PENDING);
      d.setAttempts(0);
      NotificationDelivery savedDelivery = repo.insertDelivery(d);
      repo.insertPushDelivery(
          savedDelivery.getId(), sub.id(), sub.endpoint(), sub.p256dh(), sub.auth(), payloadJson);
      written++;
    }
    return written;
  }

  /**
   * The push payload: the feed row's own words plus what the client needs to resolve a route.
   * {@code source_type}/{@code source_id} rather than the API-shaped {@code link_target} — the
   * service worker builds a page path, not an API URL. Well under the 4 KB push limit.
   */
  private String pushPayloadJson(
      UUID notificationId,
      UUID orgId,
      NotificationType type,
      NotificationTemplates.Rendered rendered,
      String sourceType,
      UUID sourceId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", notificationId.toString());
    payload.put("type", type.name());
    payload.put("title", rendered.title());
    payload.put("body", rendered.body());
    payload.put("org_id", orgId.toString());
    payload.put("source_type", sourceType);
    payload.put("source_id", sourceId == null ? null : sourceId.toString());
    try {
      return payloadMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot serialize push payload", e);
    }
  }

  /** The customer's dialable number, or null — the send target for the WhatsApp leg. */
  private String customerPhoneE164(DSLContext txDsl, UUID orgId, UUID customerId) {
    try {
      return customerRepoFactory
          .create(txDsl)
          .findById(orgId, customerId)
          .map(Customer::getPhoneE164)
          .orElse(null);
    } catch (RuntimeException e) {
      log.warn("Could not read phone_e164 for customer {}", customerId, e);
      return null;
    }
  }

  /**
   * The language to write this notification in: the customer's own {@code locale} when we have
   * learned one, else the org's {@code default_locale}, else Arabic (slice L).
   *
   * <p>A <b>USER</b> recipient resolves to the org default — {@code app_user} carries no locale,
   * and an org's staff notifications reasonably follow the store's own language. Giving staff their
   * own preference is a separate slice with its own surface (a setting nobody has asked for yet).
   *
   * <p><b>This must not throw</b>, for the same reason {@link #resolveCustomerEmail} must not:
   * {@code notify} runs inside the caller's <em>business</em> transaction, so an exception here
   * would roll back the order placement or the PAID flip, not merely pick the wrong language. A
   * missing org or an unreadable value degrades to {@link Locales#resolve}'s Arabic floor.
   *
   * <p>Costs one small org read per notification. Deliberately not cached or hoisted out of the
   * staff fan-out: these events fire at human cadence inside transactions that already do far more
   * work, and a stale locale cache would be a much worse bug than a redundant primary-key lookup.
   */
  private String resolveLocale(DSLContext txDsl, UUID orgId, NotificationRecipient recipient) {
    String orgDefault = null;
    try {
      orgDefault =
          orgRepoFactory.create(txDsl).findById(orgId).map(Org::getDefaultLocale).orElse(null);
    } catch (RuntimeException e) {
      log.warn("Could not read the default locale for org {} — falling back", orgId, e);
    }
    if (recipient.type() != RecipientType.CUSTOMER) {
      return Locales.resolve(null, orgDefault);
    }
    String customerLocale = null;
    try {
      customerLocale =
          customerRepoFactory
              .create(txDsl)
              .findById(orgId, recipient.customerId())
              .map(Customer::getLocale)
              .orElse(null);
    } catch (RuntimeException e) {
      log.warn(
          "Could not read the locale for customer {} — falling back", recipient.customerId(), e);
    }
    return Locales.resolve(customerLocale, orgDefault);
  }

  /**
   * The current email for a customer recipient, or empty when there is nowhere to send.
   *
   * <p><b>This must not throw.</b> {@code notify} runs inside the caller's <em>business</em>
   * transaction, so an exception here does not fail an email — it rolls back the order placement or
   * the PENDING_PAYMENT→PAID flip that produced the notification. A customer row whose email is
   * null or blank (anonymous checkout writes what it is given; imports and older rows exist) is a
   * suppressed channel, exactly like an opt-out preference — never a failed business event.
   */
  private Optional<String> resolveCustomerEmail(DSLContext txDsl, UUID orgId, UUID customerId) {
    CustomerRepository customers = customerRepoFactory.create(txDsl);
    Optional<Customer> c = customers.findById(orgId, customerId);
    if (c.isEmpty()) {
      log.warn(
          "Customer {} not found in org {} while resolving an email recipient", customerId, orgId);
      return Optional.empty();
    }
    String email = c.get().getEmail();
    return email == null || email.isBlank() ? Optional.empty() : Optional.of(email);
  }

  // Preferences (opt-out)

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

  // Worker: drain pending in-app deliveries

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

  // Worker: drain pending email deliveries

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

  /** What a claim produced: the row as claimed plus the frozen content to hand the provider. */
  private record ClaimedEmail(
      NotificationDelivery delivery, NotificationRepository.EmailDeliveryContent content) {}

  /**
   * Dispatch one email delivery in <b>three</b> steps — claim, send, settle — where only the first
   * and last touch the database (D4 follow-up).
   *
   * <p>It used to be one transaction wrapping all three, which meant the SMTP round-trip ran
   * holding the delivery row's lock <em>and</em> a pooled connection. With the timeouts added in D4
   * that is bounded at ~25 s rather than forever, but a handful of slow peers still pins a
   * meaningful slice of the pool, and it is the reason a second delivery node was never a safe
   * thing to run.
   *
   * <p>The claim is what makes the split possible: {@code PENDING → SENDING} commits immediately,
   * so from then on it is the row's <em>state</em>, not a held lock, that keeps other workers off
   * it.
   *
   * <p><b>The delivery guarantee is unchanged: at-least-once.</b> A crash after the provider
   * accepted the message but before the settle commits leaves the row SENDING; the reaper returns
   * it to PENDING and it sends again. That window existed before too — the old code could crash
   * between {@code send()} and the transaction commit and re-send next tick — so this moves the
   * window, it does not open one.
   */
  private DeliveryOutcome dispatchOneEmail(UUID deliveryId) {
    // 1. Claim — short transaction, no provider call inside it.
    EmailClaim claim = claimEmail(deliveryId);
    if (claim.failedTerminally()) {
      return DeliveryOutcome.FAILED;
    }
    ClaimedEmail claimed = claim.claimed();
    if (claimed == null) {
      return DeliveryOutcome.SKIPPED; // not PENDING: another tick won it, or it is already terminal
    }

    // 2. Send — NO transaction, NO connection, NO row lock. The slow part is on its own.
    RuntimeException failure = null;
    try {
      emailSender.send(
          new EmailMessage(
              claimed.content().toAddress(),
              claimed.content().subject(),
              claimed.content().renderedHtml()));
    } catch (RuntimeException e) {
      // Any provider fault — EmailException OR an out-of-contract runtime error from the sender.
      // Both must advance the attempt counter; otherwise a sender that throws e.g. an NPE would
      // leave the row retryable with attempts unchanged and be re-sent forever.
      failure = e;
    }

    // 3. Settle — short transaction. The row is SENDING, so this is the only writer for it.
    return settleEmail(claimed.delivery(), failure);
  }

  /**
   * Email's twin of {@link WhatsAppClaim} — and for the same reason: a thrown signal rolls back.
   */
  private record EmailClaim(ClaimedEmail claimed, boolean failedTerminally) {
    static EmailClaim nothing() {
      return new EmailClaim(null, false);
    }

    static EmailClaim failed() {
      return new EmailClaim(null, true);
    }

    static EmailClaim of(ClaimedEmail c) {
      return new EmailClaim(c, false);
    }
  }

  /** Claim + read content in one short transaction. */
  private EmailClaim claimEmail(UUID deliveryId) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          OffsetDateTime now = now();
          NotificationDelivery d = repo.claimForSend(deliveryId, now).orElse(null);
          if (d == null) {
            return EmailClaim.nothing();
          }
          NotificationRepository.EmailDeliveryContent content =
              repo.findEmailDeliveryContent(deliveryId).orElse(null);
          if (content == null) {
            // A producer bug, not transient — fail terminally here rather than claiming a row we
            // can never send. Committing the FAILED write means it leaves the queue for good.
            repo.markDeliveryFailed(deliveryId, "missing email subtype row", now);
            finalizeIfTerminal(repo, d.getNotificationId(), now);
            // Returned, not thrown: throwing rolled this very write back, so the row went back to
            // PENDING and was re-claimed every tick forever. The unit test could not see it — it
            // stubs transactionResult, so nothing ever rolled back there.
            return EmailClaim.failed();
          }
          return EmailClaim.of(new ClaimedEmail(d, content));
        });
  }

  /** Move a claimed row out of SENDING: SENT, back to PENDING for retry, or terminally FAILED. */
  private DeliveryOutcome settleEmail(NotificationDelivery claimed, RuntimeException failure) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          OffsetDateTime now = now();
          UUID deliveryId = claimed.getId();
          if (failure == null) {
            repo.markDeliverySent(deliveryId, now);
            finalizeIfTerminal(repo, claimed.getNotificationId(), now);
            return DeliveryOutcome.SENT;
          }
          if (claimed.getAttempts() + 1 >= emailMaxAttempts) {
            repo.markDeliveryFailed(deliveryId, truncateError(failure.getMessage()), now);
            finalizeIfTerminal(repo, claimed.getNotificationId(), now);
            return DeliveryOutcome.FAILED;
          }
          repo.markDeliveryRetry(deliveryId, truncateError(failure.getMessage()), now);
          return DeliveryOutcome.RETRIED;
        });
  }

  // Worker: drain pending WhatsApp deliveries

  /**
   * Drain PENDING WhatsApp deliveries. Deliberately the <b>same</b> claim → send → settle lease the
   * email leg uses (D4): the provider call happens outside any transaction and outside the row's
   * lock, so a slow or hung Meta endpoint pins neither a pooled connection nor a peer delivery, and
   * {@link #reapStrandedEmail} — which is channel-agnostic, it keys on {@code status = SENDING} —
   * already returns anything stranded mid-send.
   *
   * <p>At-least-once, like email. A crash after Meta accepted the message but before the settle
   * commits re-sends it; WhatsApp has no idempotency key on this endpoint, so the shopper could see
   * a duplicate. That is the same window email has always had, and it is the honest trade against
   * the alternative (marking sent before sending, which loses messages instead).
   */
  public DeliverySummary dispatchPendingWhatsApp(int batchLimit) {
    NotificationRepository reader = notificationRepoFactory.create(rootDsl);
    List<UUID> ids = reader.findPendingDeliveryIds(NotificationChannel.WHATSAPP, batchLimit);
    int sent = 0;
    int retried = 0;
    int failed = 0;
    int skipped = 0;
    for (UUID id : ids) {
      DeliveryOutcome outcome;
      try {
        outcome = dispatchOneWhatsApp(id);
      } catch (RuntimeException e) {
        outcome = DeliveryOutcome.RETRIED;
        log.warn("whatsapp delivery {} errored during dispatch (will retry)", id, e);
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

  /** The claimed row plus everything the provider call needs — content and sending identity. */
  private record ClaimedWhatsApp(
      NotificationDelivery delivery,
      NotificationRepository.WhatsAppDeliveryContent content,
      OrgWhatsAppConfig config) {}

  private DeliveryOutcome dispatchOneWhatsApp(UUID deliveryId) {
    // 1. Claim — short transaction, no provider call inside it.
    WhatsAppClaim claim = claimWhatsApp(deliveryId);
    if (claim.failedTerminally()) {
      return DeliveryOutcome.FAILED;
    }
    ClaimedWhatsApp claimed = claim.claimed();
    if (claimed == null) {
      return DeliveryOutcome.SKIPPED;
    }

    // 2. Send — NO transaction, NO connection, NO row lock.
    RuntimeException failure = null;
    String providerMessageId = null;
    try {
      providerMessageId =
          whatsAppSender.send(
              claimed.config(),
              new WhatsAppMessage(
                  claimed.content().toNumber(),
                  claimed.content().templateName(),
                  claimed.content().templateLanguage(),
                  deserializeParams(claimed.content().templateParamsJson())));
    } catch (RuntimeException e) {
      failure = e;
    }

    // 3. Settle — short transaction. The row is SENDING, so this is its only writer.
    return settleWhatsApp(claimed.delivery(), providerMessageId, failure);
  }

  /**
   * The outcome of a claim: a claimed row to send, nothing to do, or a failure already committed.
   *
   * <p>A record rather than a thrown exception <b>on purpose</b>. Signalling "this can never be
   * sent" by throwing out of {@code transactionResult} rolls the transaction back — including the
   * {@code markDeliveryFailed} written moments earlier — so the row returns to PENDING and is
   * re-claimed on every tick, forever, silently. The failure has to be committed, which means it
   * has to be returned rather than thrown.
   */
  private record WhatsAppClaim(ClaimedWhatsApp claimed, boolean failedTerminally) {
    static WhatsAppClaim nothing() {
      return new WhatsAppClaim(null, false);
    }

    static WhatsAppClaim failed() {
      return new WhatsAppClaim(null, true);
    }

    static WhatsAppClaim of(ClaimedWhatsApp c) {
      return new WhatsAppClaim(c, false);
    }
  }

  private WhatsAppClaim claimWhatsApp(UUID deliveryId) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          OffsetDateTime now = now();
          NotificationDelivery d = repo.claimForSend(deliveryId, now).orElse(null);
          if (d == null) {
            return WhatsAppClaim.nothing();
          }
          NotificationRepository.WhatsAppDeliveryContent content =
              repo.findWhatsAppDeliveryContent(deliveryId).orElse(null);
          UUID orgId = repo.findOrgIdForDelivery(deliveryId).orElse(null);
          OrgWhatsAppConfig config =
              orgId == null
                  ? null
                  : whatsAppConfigRepoFactory.create(txDsl).findByOrgId(orgId).orElse(null);
          if (content == null || config == null || !config.isActive()) {
            // Missing subtype row is a producer bug; a config that has since been disconnected or
            // disabled is a merchant action. Both are terminal for THIS delivery — retrying cannot
            // conjure credentials, and a message the merchant has stopped paying for should not be
            // re-attempted five times before anyone notices.
            repo.markDeliveryFailed(
                deliveryId,
                content == null
                    ? "missing whatsapp subtype row"
                    : "org has no active WhatsApp configuration",
                now);
            finalizeIfTerminal(repo, d.getNotificationId(), now);
            // Returned, not thrown — see WhatsAppClaim. This commits.
            return WhatsAppClaim.failed();
          }
          return WhatsAppClaim.of(new ClaimedWhatsApp(d, content, config));
        });
  }

  private DeliveryOutcome settleWhatsApp(
      NotificationDelivery claimed, String providerMessageId, RuntimeException failure) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          OffsetDateTime now = now();
          UUID deliveryId = claimed.getId();
          if (failure == null) {
            repo.markWhatsAppProviderMessageId(deliveryId, providerMessageId);
            repo.markDeliverySent(deliveryId, now);
            finalizeIfTerminal(repo, claimed.getNotificationId(), now);
            return DeliveryOutcome.SENT;
          }
          // A provider rejection of the MESSAGE (unapproved template, not a WhatsApp number,
          // revoked token) will never succeed on retry — burning four more attempts only delays
          // the FAILED that tells someone to look.
          boolean terminal = failure instanceof CloudApiWhatsAppSender.TerminalWhatsAppException;
          if (terminal || claimed.getAttempts() + 1 >= emailMaxAttempts) {
            repo.markDeliveryFailed(deliveryId, truncateError(failure.getMessage()), now);
            finalizeIfTerminal(repo, claimed.getNotificationId(), now);
            return DeliveryOutcome.FAILED;
          }
          repo.markDeliveryRetry(deliveryId, truncateError(failure.getMessage()), now);
          return DeliveryOutcome.RETRIED;
        });
  }

  // Worker: drain pending push deliveries (V96)

  /**
   * Drain PENDING push deliveries — the {@link #dispatchPendingWhatsApp} walk step for step: claim
   * in one short txn, encrypt + POST with no connection held, settle in a second short txn. One
   * delivery is one device; a phone that is gone (404/410) fails its own row and prunes its own
   * subscription while the laptop's row goes out untouched.
   */
  public DeliverySummary dispatchPendingPush(int batchLimit) {
    NotificationRepository reader = notificationRepoFactory.create(rootDsl);
    List<UUID> ids = reader.findPendingDeliveryIds(NotificationChannel.PUSH, batchLimit);
    int sent = 0;
    int retried = 0;
    int failed = 0;
    int skipped = 0;
    for (UUID id : ids) {
      DeliveryOutcome outcome;
      try {
        outcome = dispatchOnePush(id);
      } catch (RuntimeException e) {
        outcome = DeliveryOutcome.RETRIED;
        log.warn("push delivery {} errored during dispatch (will retry)", id, e);
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

  /** The claimed row plus the frozen target and payload. */
  private record ClaimedPush(
      NotificationDelivery delivery, NotificationRepository.PushDeliveryContent content) {}

  private DeliveryOutcome dispatchOnePush(UUID deliveryId) {
    // 1. Claim — short transaction, no provider call inside it.
    PushClaim claim = claimPush(deliveryId);
    if (claim.failedTerminally()) {
      return DeliveryOutcome.FAILED;
    }
    ClaimedPush claimed = claim.claimed();
    if (claimed == null) {
      return DeliveryOutcome.SKIPPED;
    }

    // 2. Send — NO transaction, NO connection, NO row lock.
    RuntimeException failure = null;
    Integer providerStatus = null;
    try {
      providerStatus =
          webPushSender.send(
              new PushTarget(
                  claimed.content().subscriptionId(),
                  claimed.content().endpoint(),
                  claimed.content().p256dh(),
                  claimed.content().auth()),
              claimed.content().payloadJson().getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      failure = e;
    }

    // 3. Settle — short transaction. The row is SENDING, so this is its only writer.
    return settlePush(claimed, providerStatus, failure);
  }

  /** Returned, not thrown — see {@link WhatsAppClaim}. */
  private record PushClaim(ClaimedPush claimed, boolean failedTerminally) {
    static PushClaim nothing() {
      return new PushClaim(null, false);
    }

    static PushClaim failed() {
      return new PushClaim(null, true);
    }

    static PushClaim of(ClaimedPush c) {
      return new PushClaim(c, false);
    }
  }

  private PushClaim claimPush(UUID deliveryId) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          OffsetDateTime now = now();
          NotificationDelivery d = repo.claimForSend(deliveryId, now).orElse(null);
          if (d == null) {
            return PushClaim.nothing();
          }
          NotificationRepository.PushDeliveryContent content =
              repo.findPushDeliveryContent(deliveryId).orElse(null);
          if (content == null || content.subscriptionId() == null) {
            // A missing subtype row is a producer bug; a null subscription_id means the device was
            // pruned (ON DELETE SET NULL) since produce — a 410 on a sibling delivery, or the user
            // unsubscribed. Both are terminal for THIS delivery: a send to a dead endpoint only
            // earns another 410. Committed by returning, never thrown.
            repo.markDeliveryFailed(
                deliveryId,
                content == null ? "missing push subtype row" : "subscription pruned before send",
                now);
            finalizeIfTerminal(repo, d.getNotificationId(), now);
            return PushClaim.failed();
          }
          return PushClaim.of(new ClaimedPush(d, content));
        });
  }

  private DeliveryOutcome settlePush(
      ClaimedPush claimed, Integer providerStatus, RuntimeException failure) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NotificationRepository repo = notificationRepoFactory.create(txDsl);
          OffsetDateTime now = now();
          UUID deliveryId = claimed.delivery().getId();
          UUID subscriptionId = claimed.content().subscriptionId();
          if (failure == null) {
            repo.markPushProviderStatus(deliveryId, providerStatus);
            repo.markDeliverySent(deliveryId, now);
            if (pushSubscriptionRepoFactory != null && subscriptionId != null) {
              pushSubscriptionRepoFactory.create(txDsl).touchLastUsed(subscriptionId, now);
            }
            finalizeIfTerminal(repo, claimed.delivery().getNotificationId(), now);
            return DeliveryOutcome.SENT;
          }
          if (failure instanceof WebPushException wpe) {
            repo.markPushProviderStatus(deliveryId, wpe.status());
          }
          boolean terminal = failure instanceof WebPushException.TerminalWebPushException;
          boolean gone = terminal && ((WebPushException.TerminalWebPushException) failure).isGone();
          if (terminal || claimed.delivery().getAttempts() + 1 >= pushMaxAttempts) {
            repo.markDeliveryFailed(deliveryId, truncateError(failure.getMessage()), now);
            if (gone && pushSubscriptionRepoFactory != null && subscriptionId != null) {
              // The push service's verdict on the DEVICE, not the message: it unsubscribed or the
              // endpoint expired. The only moment the server learns it — prune, so the next event
              // does not produce a row for a phone that is not there.
              PushSubscriptionRepository subs = pushSubscriptionRepoFactory.create(txDsl);
              if (subs.deleteById(subscriptionId) > 0) {
                log.info("pruned push subscription {} (push service said gone)", subscriptionId);
              }
            }
            finalizeIfTerminal(repo, claimed.delivery().getNotificationId(), now);
            return DeliveryOutcome.FAILED;
          }
          repo.markDeliveryRetry(deliveryId, truncateError(failure.getMessage()), now);
          return DeliveryOutcome.RETRIED;
        });
  }

  private List<String> deserializeParams(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return payloadMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      // The row is unusable; let the caller's failure path mark it terminally FAILED.
      throw new IllegalStateException("cannot read stored whatsapp template params", e);
    }
  }

  /**
   * Return deliveries stranded in SENDING to the queue — the other half of the lease.
   *
   * <p>A worker that dies between claiming and settling leaves a row SENDING with nobody coming
   * back for it, and the sweeper only looks for PENDING, so without this it is a permanent silent
   * loss. A stranded claim <b>counts as an attempt</b>: otherwise a message that reliably kills the
   * process would be reclaimed forever, which is the one failure mode a retry budget exists to
   * stop.
   *
   * @param leaseSeconds how long a claim may be outstanding before it is presumed dead. Must exceed
   *     the worst-case send — the SMTP timeouts bound that at ~25 s — or a live send gets reaped
   *     underneath itself and the message goes twice.
   */
  public DeliverySummary reapStrandedEmail(long leaseSeconds, int batchLimit) {
    OffsetDateTime cutoff = now().minusSeconds(leaseSeconds);
    List<UUID> ids =
        notificationRepoFactory.create(rootDsl).findStrandedSendingIds(cutoff, batchLimit);
    int retried = 0;
    int failed = 0;
    for (UUID id : ids) {
      DeliveryOutcome outcome =
          rootDsl.transactionResult(
              cfg -> {
                DSLContext txDsl = DSL.using(cfg);
                NotificationRepository repo = notificationRepoFactory.create(txDsl);
                NotificationDelivery d = repo.findDeliveryById(id).orElse(null);
                if (d == null || d.getStatus() != DeliveryStatus.SENDING) {
                  return DeliveryOutcome.SKIPPED; // settled or reaped between the read and now
                }
                OffsetDateTime now = now();
                if (d.getAttempts() + 1 >= emailMaxAttempts) {
                  repo.markDeliveryFailed(id, "stranded mid-send; attempts exhausted", now);
                  finalizeIfTerminal(repo, d.getNotificationId(), now);
                  return DeliveryOutcome.FAILED;
                }
                repo.markDeliveryRetry(id, "stranded mid-send; returned to the queue", now);
                return DeliveryOutcome.RETRIED;
              });
      switch (outcome) {
        case RETRIED -> retried++;
        case FAILED -> failed++;
        default -> {}
      }
    }
    if (retried + failed > 0) {
      log.warn(
          "reaped {} stranded email deliveries ({} failed terminally)", retried + failed, failed);
    }
    return new DeliverySummary(ids.size(), 0, retried, failed, ids.size() - retried - failed);
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

  // Feed (own-only)

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

  // helpers

  /** The template's ordered parameters as a JSON array, for the subtype row's audit trail. */
  private String serializeParams(java.util.List<String> params) {
    if (params == null || params.isEmpty()) {
      return null;
    }
    try {
      return payloadMapper.writeValueAsString(params);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot serialize whatsapp template params", e);
    }
  }

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
