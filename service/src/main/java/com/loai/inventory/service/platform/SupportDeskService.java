package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.DeskTicketRow;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.PlatformQueueOrg;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.TicketCloseReason;
import com.loai.inventory.domain.model.TicketDeskCounts;
import com.loai.inventory.domain.model.TicketMessage;
import com.loai.inventory.domain.model.TicketSide;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.domain.repository.PlatformTicketRepository;
import com.loai.inventory.domain.repository.PlatformTicketRepositoryFactory;
import com.loai.inventory.domain.repository.SupportTicketRepository;
import com.loai.inventory.domain.repository.SupportTicketRepositoryFactory;
import com.loai.inventory.service.SupportTicketService;
import com.loai.inventory.service.SupportTicketService.AttachmentInput;
import com.loai.inventory.service.SupportTicketService.Presign;
import com.loai.inventory.service.SupportTicketService.TicketView;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The operator's side of the support desk ({@code stories/support_tickets.md}): the cross-org inbox
 * (through the fifth enumerated sibling, {@link PlatformTicketRepository}), the counts, and the
 * desk's verbs — reply, resolve, close — each a transition on the same {@link SupportTicket} the
 * merchant writes, recorded on the tenant's timeline through {@link PlatformAuditService} and told
 * to the merchant participants inside the same transaction.
 *
 * <p>Writes go through the <em>org-scoped</em> repository once {@link
 * PlatformTicketRepository#findOrgId} has named the tenant; the sibling never mutates.
 */
public class SupportDeskService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  public static final String TARGET_TICKET = "TICKET";
  public static final String ACTION_REPLIED = "SUPPORT_TICKET_REPLIED";
  public static final String ACTION_RESOLVED = "SUPPORT_TICKET_RESOLVED";
  public static final String ACTION_CLOSED = "SUPPORT_TICKET_CLOSED";

  private final DSLContext rootDsl;
  private final PlatformTicketRepositoryFactory deskRepoFactory;
  private final SupportTicketRepositoryFactory ticketRepoFactory;
  private final SupportTicketService tickets;
  private final PlatformAuditService audit;

  public SupportDeskService(
      DSLContext rootDsl,
      PlatformTicketRepositoryFactory deskRepoFactory,
      SupportTicketRepositoryFactory ticketRepoFactory,
      SupportTicketService tickets,
      PlatformAuditService audit) {
    this.rootDsl = rootDsl;
    this.deskRepoFactory = deskRepoFactory;
    this.ticketRepoFactory = ticketRepoFactory;
    this.tickets = tickets;
    this.audit = audit;
  }

  public record DeskPage(List<DeskTicketRow> rows, long total, int page, int size) {}

  /** The single-ticket desk read: the thread (notes included), the tenant, the opener's email. */
  public record DeskView(TicketView view, PlatformQueueOrg org) {}

  /** Parse {@code ?status=}; null/blank = every status; unknown → 400 naming the four. */
  public static TicketStatus parseStatus(String raw) {
    try {
      return TicketStatus.fromWire(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "Unknown status: '" + raw + "'. Expected one of: " + TicketStatus.allWireValues());
    }
  }

  /** One page of the inbox; {@code OPEN} is the queue, the rest ledgers (see the repository). */
  public DeskPage list(TicketStatus status, UUID orgId, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = PlatformOrgService.safeOffset(p, s);
    PlatformTicketRepository repo = deskRepoFactory.create(rootDsl);
    return new DeskPage(repo.page(status, orgId, offset, s), repo.count(status, orgId), p, s);
  }

  public TicketDeskCounts counts(UUID orgId) {
    return deskRepoFactory.create(rootDsl).counts(orgId);
  }

  public DeskView get(UUID ticketId) {
    UUID orgId = orgIdOf(rootDsl, ticketId);
    SupportTicket ticket =
        ticketRepoFactory
            .create(rootDsl)
            .findById(orgId, ticketId)
            .orElseThrow(() -> new NotFoundException("SupportTicket", ticketId));
    return new DeskView(tickets.viewInTx(rootDsl, ticket, true), orgSummary(rootDsl, orgId));
  }

  /**
   * The desk writes: {@code → AWAITING_MERCHANT}, or {@code → RESOLVED} when {@code resolve}; a
   * resolved ticket stays resolved. Audited on the tenant's timeline; the merchant participants are
   * told ({@code SUPPORT_TICKET_REPLIED}, or {@code _RESOLVED} when this resolved it).
   */
  public DeskView reply(
      SecurityContext sc,
      Environment env,
      UUID ticketId,
      String rawBody,
      List<AttachmentInput> rawAttachments,
      boolean resolve) {
    String body = SupportTicketService.requireText(rawBody, "body", SupportTicketService.BODY_MAX);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return transition(
        sc,
        env,
        ticketId,
        (txDsl, ticket) -> {
          List<AttachmentInput> attachments =
              tickets.validateAttachments(ticket.getOrgId(), rawAttachments);
          SupportTicketRepository repo = ticketRepoFactory.create(txDsl);
          TicketStatus entered = ticket.supportMessage(sc.actorId(), resolve, now);
          UUID messageId = UUID.randomUUID();
          repo.insertMessage(
              TicketMessage.message(
                  messageId,
                  ticket.getOrgId(),
                  ticket.getId(),
                  TicketSide.SUPPORT,
                  sc.actorId(),
                  body,
                  now));
          tickets.appendAttachmentsInTx(txDsl, ticket, messageId, attachments, now);
          tickets.recordTransitionInTx(
              repo, ticket, TicketSide.SUPPORT, sc.actorId(), entered, now);
          repo.update(ticket);
          audit.recordInTx(
              txDsl,
              sc,
              env,
              ticket.getOrgId(),
              ACTION_REPLIED,
              TARGET_TICKET,
              ticket.getId(),
              Map.of("number", ticket.getNumber(), "resolved", entered == TicketStatus.RESOLVED));
          tickets.notifyMerchantsInTx(
              txDsl,
              ticket,
              entered == TicketStatus.RESOLVED
                  ? NotificationType.SUPPORT_TICKET_RESOLVED
                  : NotificationType.SUPPORT_TICKET_REPLIED);
          return ticket;
        });
  }

  /** Resolve without words: {@code OPEN | AWAITING_MERCHANT → RESOLVED}. */
  public DeskView resolve(SecurityContext sc, Environment env, UUID ticketId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return transition(
        sc,
        env,
        ticketId,
        (txDsl, ticket) -> {
          SupportTicketRepository repo = ticketRepoFactory.create(txDsl);
          TicketStatus entered = ticket.resolve(sc.actorId(), now);
          tickets.recordTransitionInTx(
              repo, ticket, TicketSide.SUPPORT, sc.actorId(), entered, now);
          repo.update(ticket);
          audit.recordInTx(
              txDsl,
              sc,
              env,
              ticket.getOrgId(),
              ACTION_RESOLVED,
              TARGET_TICKET,
              ticket.getId(),
              Map.of("number", ticket.getNumber()));
          tickets.notifyMerchantsInTx(txDsl, ticket, NotificationType.SUPPORT_TICKET_RESOLVED);
          return ticket;
        });
  }

  /** The desk closes: terminal, audited, and deliberately silent (a merchant closed nothing). */
  public DeskView close(SecurityContext sc, Environment env, UUID ticketId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return transition(
        sc,
        env,
        ticketId,
        (txDsl, ticket) -> {
          SupportTicketRepository repo = ticketRepoFactory.create(txDsl);
          TicketStatus entered = ticket.close(sc.actorId(), TicketCloseReason.SUPPORT, now);
          tickets.recordTransitionInTx(
              repo, ticket, TicketSide.SUPPORT, sc.actorId(), entered, now);
          repo.update(ticket);
          audit.recordInTx(
              txDsl,
              sc,
              env,
              ticket.getOrgId(),
              ACTION_CLOSED,
              TARGET_TICKET,
              ticket.getId(),
              Map.of("number", ticket.getNumber()));
          return ticket;
        });
  }

  /**
   * A presigned PUT under the <em>ticket's</em> org prefix, so the desk's screenshot lives with the
   * merchant's.
   */
  public Presign presignAttachment(UUID ticketId, String filename, String contentType) {
    UUID orgId = orgIdOf(rootDsl, ticketId);
    return tickets.presignAttachment(orgId, filename, contentType);
  }

  private DeskView transition(
      SecurityContext sc,
      Environment env,
      UUID ticketId,
      BiFunction<DSLContext, SupportTicket, SupportTicket> body) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          UUID orgId = orgIdOf(txDsl, ticketId);
          SupportTicket ticket =
              ticketRepoFactory
                  .create(txDsl)
                  .findByIdForUpdate(orgId, ticketId)
                  .orElseThrow(() -> new NotFoundException("SupportTicket", ticketId));
          SupportTicket after = body.apply(txDsl, ticket);
          return new DeskView(tickets.viewInTx(txDsl, after, true), orgSummary(txDsl, orgId));
        });
  }

  private UUID orgIdOf(DSLContext dsl, UUID ticketId) {
    return deskRepoFactory
        .create(dsl)
        .findOrgId(ticketId)
        .orElseThrow(() -> new NotFoundException("SupportTicket", ticketId));
  }

  private PlatformQueueOrg orgSummary(DSLContext dsl, UUID orgId) {
    Org org = tickets.org(dsl, orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
    return new PlatformQueueOrg(org.getId(), org.getName(), org.getSlug(), OrgStatus.of(org));
  }
}
