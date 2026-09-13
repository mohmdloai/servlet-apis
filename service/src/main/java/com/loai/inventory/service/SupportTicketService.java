package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.TicketCapException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.TicketAttachment;
import com.loai.inventory.domain.model.TicketCategory;
import com.loai.inventory.domain.model.TicketCloseReason;
import com.loai.inventory.domain.model.TicketMessage;
import com.loai.inventory.domain.model.TicketMessageKind;
import com.loai.inventory.domain.model.TicketRef;
import com.loai.inventory.domain.model.TicketSide;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.SupportTicketRepository;
import com.loai.inventory.domain.repository.SupportTicketRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The merchant's side of the support desk ({@code stories/support_tickets.md}): open a ticket, read
 * the org's tickets, write on a thread, close one. <strong>A message is the transition</strong> —
 * the status changes are {@link SupportTicket}'s; this service owns the transaction, the cap, the
 * attachment guards, the thread rows and the notifications, all inside one transaction so a ticket
 * that exists has told the desk (the rule that outranks the rest).
 *
 * <p>Own-vs-all is decided here from the actor and the caller's manager authority (the cash-shift
 * precedent): a STAFF member sees and writes only the tickets they opened; MANAGER and up see the
 * org's. A foreign ticket answers the opaque 404, never a 403 that confirms it exists.
 *
 * <p>The desk ({@code SupportDeskService}) composes the {@code *InTx} helpers below over the same
 * repository, once it has named the tenant — one thread writer, two planes.
 */
public class SupportTicketService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /** Not-closed tickets an org may hold at once — a sanity rail, not a rate limit. */
  public static final int OPEN_CAP = 10;

  public static final int SUBJECT_MAX = 120;
  public static final int BODY_MAX = 4000;
  public static final int MAX_ATTACHMENTS_PER_MESSAGE = 3;

  public static final String SOURCE_TYPE = "support_ticket";

  private static final Set<OrgRole> MEMBER_ROLES =
      Set.of(OrgRole.VIEWER, OrgRole.STAFF, OrgRole.MANAGER, OrgRole.OWNER);

  /**
   * The ticket paths' clock, truncated to microseconds — Postgres keeps {@code timestamptz} at µs,
   * so a stamp the caller gets back in the write's own response ({@code last_activity_at}, {@code
   * status_since}, {@code resolved_at}) carries the precision it will read back with later. The JDK
   * clock is ns on Linux and µs on macOS, so without this the same instant is two different values
   * on one ticket — CI caught it on {@code first_response_at}.
   */
  public static OffsetDateTime ticketClock() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private final DSLContext rootDsl;
  private final SupportTicketRepositoryFactory ticketRepoFactory;
  private final UserRepositoryFactory userRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final NotificationService notificationService;
  private final ObjectStorage storage;

  public SupportTicketService(
      DSLContext rootDsl,
      SupportTicketRepositoryFactory ticketRepoFactory,
      UserRepositoryFactory userRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      NotificationService notificationService,
      ObjectStorage storage) {
    this.rootDsl = rootDsl;
    this.ticketRepoFactory = ticketRepoFactory;
    this.userRepoFactory = userRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.notificationService = notificationService;
    this.storage = storage;
  }

  // Views

  /** A person on the thread; {@code email} is carried but only the desk's DTO writes it. */
  public record Person(UUID id, String displayName, String email) {}

  /** An attachment with a fresh presigned URL, or {@code null} when its key failed the guard. */
  public record AttachmentView(TicketAttachment attachment, String url) {}

  public record MessageView(
      TicketMessage message, Person author, List<AttachmentView> attachments) {}

  public record TicketSummaryView(
      SupportTicket ticket, Person openedBy, int attachmentCount, String lastMessagePreview) {}

  public record TicketView(
      SupportTicket ticket,
      Person openedBy,
      List<MessageView> messages,
      int attachmentCount,
      String lastMessagePreview) {}

  public record TicketPage(List<TicketSummaryView> items, long total) {}

  public record AttachmentInput(String objectKey, String contentType, String fileName) {}

  public record OpenCommand(
      TicketCategory category,
      String subject,
      String body,
      boolean blocking,
      TicketRef ref,
      List<AttachmentInput> attachments) {}

  public record Presign(String uploadUrl, String objectKey, long expiresInSeconds) {}

  // Org plane

  /**
   * Open a ticket: the first message, its screenshots, and the desk's notification rows — one
   * transaction. The eleventh not-closed ticket is a {@link TicketCapException}, counted under the
   * org row's lock so two phones cannot race past it.
   */
  public TicketView open(UUID orgId, UUID actor, OpenCommand cmd) {
    if (cmd == null || cmd.category() == null) {
      throw new ValidationException(
          "category is required: one of " + java.util.Arrays.toString(TicketCategory.values()));
    }
    String subject = requireText(cmd.subject(), "subject", SUBJECT_MAX);
    String body = requireText(cmd.body(), "body", BODY_MAX);
    List<AttachmentInput> attachments = validateAttachments(orgId, cmd.attachments());
    TicketRef ref = validateRef(cmd.ref());
    OffsetDateTime now = ticketClock();

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SupportTicketRepository repo = ticketRepoFactory.create(txDsl);
          if (repo.countNotClosedLocked(orgId) >= OPEN_CAP) {
            throw new TicketCapException(OPEN_CAP);
          }
          SupportTicket ticket =
              SupportTicket.open(
                  UUID.randomUUID(),
                  orgId,
                  actor,
                  cmd.category(),
                  subject,
                  cmd.blocking(),
                  ref,
                  now);
          repo.insert(ticket);
          UUID messageId = UUID.randomUUID();
          repo.insertMessage(
              TicketMessage.message(
                  messageId, orgId, ticket.getId(), TicketSide.MERCHANT, actor, body, now));
          appendAttachmentsInTx(txDsl, ticket, messageId, attachments, now);
          notifyDeskInTx(txDsl, ticket, NotificationType.SUPPORT_TICKET_OPENED, null);
          return viewInTx(txDsl, ticket, false);
        });
  }

  /** The org's ledger, newest activity first; STAFF sees only their own. */
  public TicketPage list(
      UUID orgId, UUID actor, boolean isManager, TicketStatus status, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    UUID openedBy = isManager ? null : actor;
    SupportTicketRepository repo = ticketRepoFactory.create(rootDsl);
    List<SupportTicket> tickets = repo.list(orgId, status, openedBy, p * s, s);
    long total = repo.count(orgId, status, openedBy);
    Map<UUID, SupportTicketRepository.ListExtras> extras =
        repo.listExtras(tickets.stream().map(SupportTicket::getId).toList());
    Map<UUID, Person> people = new HashMap<>();
    UserRepository users = userRepoFactory.create(rootDsl);
    List<TicketSummaryView> items = new ArrayList<>(tickets.size());
    for (SupportTicket t : tickets) {
      SupportTicketRepository.ListExtras x =
          extras.getOrDefault(t.getId(), new SupportTicketRepository.ListExtras(0, null));
      items.add(
          new TicketSummaryView(
              t,
              person(users, people, t.getOpenedBy()),
              x.attachmentCount(),
              x.lastMessagePreview()));
    }
    return new TicketPage(items, total);
  }

  /** One ticket with its thread (never a NOTE on this plane); opaque 404 for a foreign one. */
  public TicketView get(UUID orgId, UUID actor, boolean isManager, UUID ticketId) {
    SupportTicket ticket =
        ticketRepoFactory
            .create(rootDsl)
            .findById(orgId, ticketId)
            .filter(t -> visible(t, actor, isManager))
            .orElseThrow(() -> new NotFoundException("SupportTicket", ticketId));
    return viewInTx(rootDsl, ticket, false);
  }

  /**
   * The merchant writes: the ticket becomes the desk's again ({@code → OPEN}; from {@code RESOLVED}
   * that is the reopen). A {@code CLOSED} ticket refuses with {@code TICKET_CLOSED}.
   */
  public TicketView post(
      UUID orgId,
      UUID actor,
      boolean isManager,
      UUID ticketId,
      String rawBody,
      List<AttachmentInput> rawAttachments) {
    String body = requireText(rawBody, "body", BODY_MAX);
    List<AttachmentInput> attachments = validateAttachments(orgId, rawAttachments);
    OffsetDateTime now = ticketClock();
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SupportTicketRepository repo = ticketRepoFactory.create(txDsl);
          SupportTicket ticket = lockVisible(repo, orgId, actor, isManager, ticketId);
          TicketStatus before = ticket.getStatus();
          TicketStatus entered = ticket.merchantMessage(now);
          UUID messageId = UUID.randomUUID();
          repo.insertMessage(
              TicketMessage.message(
                  messageId, orgId, ticketId, TicketSide.MERCHANT, actor, body, now));
          appendAttachmentsInTx(txDsl, ticket, messageId, attachments, now);
          recordTransitionInTx(repo, ticket, TicketSide.MERCHANT, actor, entered, now);
          repo.update(ticket);
          String event = before == TicketStatus.RESOLVED ? "reopened" : "replied";
          notifyDeskInTx(txDsl, ticket, NotificationType.SUPPORT_TICKET_UPDATED, event);
          return viewInTx(txDsl, ticket, false);
        });
  }

  /** The merchant closes their ticket — terminal; the desk is told, nobody else. */
  public TicketView close(UUID orgId, UUID actor, boolean isManager, UUID ticketId) {
    OffsetDateTime now = ticketClock();
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SupportTicketRepository repo = ticketRepoFactory.create(txDsl);
          SupportTicket ticket = lockVisible(repo, orgId, actor, isManager, ticketId);
          TicketStatus entered = ticket.close(actor, TicketCloseReason.MERCHANT, now);
          recordTransitionInTx(repo, ticket, TicketSide.MERCHANT, actor, entered, now);
          repo.update(ticket);
          notifyDeskInTx(txDsl, ticket, NotificationType.SUPPORT_TICKET_UPDATED, "closed");
          return viewInTx(txDsl, ticket, false);
        });
  }

  /**
   * The auto-close sweep ({@code stories/support_ticket_reach.md}): every {@code RESOLVED} ticket
   * whose {@code resolved_at} is more than {@code days} ago becomes {@code CLOSED} with {@code
   * closed_reason = AUTO}, {@code closed_by} null and a {@code STATUS} row with no author. One
   * bounded read of candidates, then <b>one transaction per ticket</b> under {@code FOR UPDATE SKIP
   * LOCKED}, re-checking the predicate — a ticket the merchant reopened in between is left alone.
   * <b>No notification</b>: a merchant who did not answer a resolution in seven days is not waiting
   * for news of it. Returns how many closed. {@code now} comes from {@link #ticketClock()}.
   */
  public int autoClose(OffsetDateTime now, int days, int batch) {
    OffsetDateTime cutoff = now.minusDays(days);
    List<UUID> candidates =
        ticketRepoFactory.create(rootDsl).findAutoCloseCandidates(cutoff, batch);
    int closed = 0;
    for (UUID id : candidates) {
      boolean done =
          rootDsl.transactionResult(
              cfg -> {
                SupportTicketRepository repo = ticketRepoFactory.create(DSL.using(cfg));
                Optional<SupportTicket> locked = repo.lockAutoCloseCandidate(id, cutoff);
                if (locked.isEmpty()) {
                  return false;
                }
                SupportTicket ticket = locked.get();
                TicketStatus entered = ticket.close(null, TicketCloseReason.AUTO, now);
                recordTransitionInTx(repo, ticket, TicketSide.SUPPORT, null, entered, now);
                repo.update(ticket);
                return true;
              });
      if (done) {
        closed++;
      }
    }
    return closed;
  }

  /**
   * A presigned PUT under the org's support prefix; images only — the presigner's first allowlist.
   */
  public Presign presignAttachment(UUID orgId, String filename, String contentType) {
    if (!ObjectStorage.isSupportImageType(contentType)) {
      throw new ValidationException(
          "content_type must be one of " + String.join(", ", ObjectStorage.SUPPORT_IMAGE_TYPES));
    }
    String key = storage.newSupportAttachmentKey(orgId, filename);
    return new Presign(
        storage.presignPut(key, contentType.trim()), key, storage.presignTtlSeconds());
  }

  // Shared with the desk

  /** The org row, for the desk's tenant card and the notification payload. */
  public Optional<Org> org(DSLContext dsl, UUID orgId) {
    return orgRepoFactory.create(dsl).findById(orgId);
  }

  /** Attach validated screenshots to a message — the write-side prefix guard already ran. */
  public void appendAttachmentsInTx(
      DSLContext txDsl,
      SupportTicket ticket,
      UUID messageId,
      List<AttachmentInput> attachments,
      OffsetDateTime now) {
    if (attachments.isEmpty()) {
      return;
    }
    SupportTicketRepository repo = ticketRepoFactory.create(txDsl);
    for (AttachmentInput a : attachments) {
      repo.insertAttachment(
          new TicketAttachment(
              UUID.randomUUID(),
              ticket.getOrgId(),
              ticket.getId(),
              messageId,
              a.objectKey(),
              a.contentType(),
              a.fileName(),
              now));
    }
  }

  /**
   * Write the {@code STATUS} row for a transition the aggregate just made ({@code entered} is null
   * when the message changed nothing). Stamped one microsecond after the message so the thread
   * reads "message, then the line" deterministically.
   */
  public void recordTransitionInTx(
      SupportTicketRepository repo,
      SupportTicket ticket,
      TicketSide side,
      UUID actor,
      TicketStatus entered,
      OffsetDateTime now) {
    if (entered == null) {
      return;
    }
    repo.insertMessage(
        TicketMessage.status(
            UUID.randomUUID(),
            ticket.getOrgId(),
            ticket.getId(),
            side,
            actor,
            entered,
            now.plusNanos(1_000)));
  }

  /**
   * Tell every active platform ADMIN / SUPPORT user, each as a {@code USER} recipient with {@code
   * org_id} = the ticket's org. In-app + push today; the console's feed is slice 2.
   */
  public void notifyDeskInTx(
      DSLContext txDsl, SupportTicket ticket, NotificationType type, String event) {
    String orgName = org(txDsl, ticket.getOrgId()).map(Org::getName).orElse("");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ticket_number", String.valueOf(ticket.getNumber()));
    payload.put("subject", ticket.getSubject());
    payload.put("org_name", orgName);
    payload.put("blocking", String.valueOf(ticket.isBlocking()));
    if (event != null) {
      payload.put("event", event);
    }
    for (UUID userId : userRepoFactory.create(txDsl).activeDeskUserIds()) {
      notificationService.notify(
          txDsl,
          ticket.getOrgId(),
          NotificationRecipient.user(userId),
          type,
          payload,
          SOURCE_TYPE,
          ticket.getId(),
          null);
    }
  }

  /**
   * Tell the merchant participants — every active member who wrote on the merchant side (the opener
   * always is one); the org's OWNERs when none remain.
   */
  public void notifyMerchantsInTx(DSLContext txDsl, SupportTicket ticket, NotificationType type) {
    UserRepository users = userRepoFactory.create(txDsl);
    Set<UUID> members = users.findActiveUserIdsByOrgAndRoles(ticket.getOrgId(), MEMBER_ROLES);
    Set<UUID> recipients = new HashSet<>();
    for (UUID id : ticketRepoFactory.create(txDsl).merchantParticipantIds(ticket.getId())) {
      if (members.contains(id)) {
        recipients.add(id);
      }
    }
    if (recipients.isEmpty()) {
      recipients.addAll(
          users.findActiveUserIdsByOrgAndRoles(ticket.getOrgId(), Set.of(OrgRole.OWNER)));
    }
    Map<String, Object> payload =
        Map.of(
            "ticket_number", String.valueOf(ticket.getNumber()),
            "subject", ticket.getSubject());
    for (UUID userId : recipients) {
      notificationService.notify(
          txDsl,
          ticket.getOrgId(),
          NotificationRecipient.user(userId),
          type,
          payload,
          SOURCE_TYPE,
          ticket.getId(),
          null);
    }
  }

  /**
   * The ticket with its thread; every attachment gets a fresh presigned GET after the read guard.
   */
  public TicketView viewInTx(DSLContext dsl, SupportTicket ticket, boolean includeNotes) {
    SupportTicketRepository repo = ticketRepoFactory.create(dsl);
    UserRepository users = userRepoFactory.create(dsl);
    Map<UUID, Person> people = new HashMap<>();
    Map<UUID, List<AttachmentView>> byMessage = new HashMap<>();
    int attachmentCount = 0;
    String prefix = ObjectStorage.supportKeyPrefix(ticket.getOrgId());
    for (TicketAttachment a : repo.findAttachments(ticket.getId())) {
      attachmentCount++;
      // The read-side guard: never mint a credential for a key outside this org's prefix, even if
      // one ever reached the column. Null → the client says "couldn't load", never a broken image.
      String url = a.objectKey().startsWith(prefix) ? storage.presignGet(a.objectKey()) : null;
      byMessage
          .computeIfAbsent(a.messageId(), k -> new ArrayList<>())
          .add(new AttachmentView(a, url));
    }
    List<MessageView> messages = new ArrayList<>();
    String lastPreview = null;
    for (TicketMessage m : repo.findMessages(ticket.getId(), includeNotes)) {
      Person author = m.authorId() == null ? null : person(users, people, m.authorId());
      messages.add(new MessageView(m, author, byMessage.getOrDefault(m.id(), List.of())));
      if (m.kind() == TicketMessageKind.MESSAGE) {
        lastPreview = m.body();
      }
    }
    return new TicketView(
        ticket,
        person(users, people, ticket.getOpenedBy()),
        messages,
        attachmentCount,
        lastPreview == null ? null : preview(lastPreview));
  }

  public List<AttachmentInput> validateAttachments(UUID orgId, List<AttachmentInput> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    if (raw.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
      throw new ValidationException(
          "at most " + MAX_ATTACHMENTS_PER_MESSAGE + " attachments per message");
    }
    String prefix = ObjectStorage.supportKeyPrefix(orgId);
    List<AttachmentInput> out = new ArrayList<>(raw.size());
    for (AttachmentInput a : raw) {
      if (a == null || a.objectKey() == null || !a.objectKey().startsWith(prefix)) {
        throw new ValidationException("object_key must be a support attachment key of this org");
      }
      if (!ObjectStorage.isSupportImageType(a.contentType())) {
        throw new ValidationException(
            "content_type must be one of " + String.join(", ", ObjectStorage.SUPPORT_IMAGE_TYPES));
      }
      String name = Text.normalizeText(a.fileName());
      out.add(
          new AttachmentInput(
              a.objectKey(),
              a.contentType().trim().toLowerCase(java.util.Locale.ROOT),
              name == null ? "screenshot" : name));
    }
    return out;
  }

  public static String requireText(String raw, String field, int max) {
    String s = Text.normalizeText(raw);
    if (s == null) {
      throw new ValidationException(field + " is required");
    }
    if (s.length() > max) {
      throw new ValidationException(field + " must be at most " + max + " characters");
    }
    return s;
  }

  // Helpers

  private static TicketRef validateRef(TicketRef ref) {
    if (ref == null) {
      return null;
    }
    if (ref.type() == null || ref.id() == null) {
      throw new ValidationException("ref needs both type and id");
    }
    String label = Text.normalizeText(ref.label());
    return new TicketRef(ref.type(), ref.id(), label);
  }

  private static boolean visible(SupportTicket t, UUID actor, boolean isManager) {
    return isManager || t.getOpenedBy().equals(actor);
  }

  private static SupportTicket lockVisible(
      SupportTicketRepository repo, UUID orgId, UUID actor, boolean isManager, UUID ticketId) {
    return repo.findByIdForUpdate(orgId, ticketId)
        .filter(t -> visible(t, actor, isManager))
        .orElseThrow(() -> new NotFoundException("SupportTicket", ticketId));
  }

  private static Person person(UserRepository users, Map<UUID, Person> cache, UUID id) {
    return cache.computeIfAbsent(
        id,
        k ->
            users
                .findById(k)
                .map(u -> new Person(u.getId(), displayName(u), u.getEmail()))
                .orElse(new Person(k, null, null)));
  }

  private static String displayName(AppUser u) {
    String n = u.getDisplayName();
    if (n != null && !n.isBlank()) {
      return n;
    }
    String email = u.getEmail();
    return email == null ? null : email;
  }

  private static String preview(String body) {
    String flat = body.replaceAll("\\s+", " ").trim();
    return flat.length() <= 120 ? flat : flat.substring(0, 119) + "…";
  }
}
