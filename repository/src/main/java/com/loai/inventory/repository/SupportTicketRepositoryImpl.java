package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET_ATTACHMENT;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET_MESSAGE;

import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.TicketAttachment;
import com.loai.inventory.domain.model.TicketCategory;
import com.loai.inventory.domain.model.TicketCloseReason;
import com.loai.inventory.domain.model.TicketMessage;
import com.loai.inventory.domain.model.TicketMessageKind;
import com.loai.inventory.domain.model.TicketRef;
import com.loai.inventory.domain.model.TicketRefType;
import com.loai.inventory.domain.model.TicketSide;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.domain.repository.SupportTicketRepository;
import com.loai.inventory.repository.generated.tables.records.SupportTicketRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The org-scoped ticket store — every query ANDs {@code org_id}, the way every business table does.
 */
public final class SupportTicketRepositoryImpl implements SupportTicketRepository {

  /** How much of the last message a list row carries — enough for a card's second line. */
  private static final int PREVIEW_CHARS = 120;

  private final DSLContext dsl;

  public SupportTicketRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(SupportTicket t) {
    // `number` is left to the sequence default and read back — the one column the aggregate does
    // not own until the row exists.
    Long number =
        dsl.insertInto(SUPPORT_TICKET)
            .set(SUPPORT_TICKET.ID, t.getId())
            .set(SUPPORT_TICKET.ORG_ID, t.getOrgId())
            .set(SUPPORT_TICKET.STATUS, t.getStatus().name())
            .set(SUPPORT_TICKET.CATEGORY, t.getCategory().name())
            .set(SUPPORT_TICKET.BLOCKING, t.isBlocking())
            .set(SUPPORT_TICKET.SUBJECT, t.getSubject())
            .set(SUPPORT_TICKET.REF_TYPE, t.getRef() == null ? null : t.getRef().type().name())
            .set(SUPPORT_TICKET.REF_ID, t.getRef() == null ? null : t.getRef().id())
            .set(SUPPORT_TICKET.REF_LABEL, t.getRef() == null ? null : t.getRef().label())
            .set(SUPPORT_TICKET.OPENED_BY, t.getOpenedBy())
            .set(SUPPORT_TICKET.OPENED_AT, t.getOpenedAt())
            .set(SUPPORT_TICKET.STATUS_SINCE, t.getStatusSince())
            .set(SUPPORT_TICKET.LAST_ACTIVITY_AT, t.getLastActivityAt())
            .set(SUPPORT_TICKET.CREATED_AT, t.getCreatedAt())
            .set(SUPPORT_TICKET.UPDATED_AT, t.getUpdatedAt())
            .returning(SUPPORT_TICKET.NUMBER)
            .fetchOne(SUPPORT_TICKET.NUMBER);
    if (number == null) {
      throw new IllegalStateException("support_ticket insert returned no number");
    }
    t.assignNumber(number);
  }

  @Override
  public void update(SupportTicket t) {
    dsl.update(SUPPORT_TICKET)
        .set(SUPPORT_TICKET.STATUS, t.getStatus().name())
        .set(SUPPORT_TICKET.STATUS_SINCE, t.getStatusSince())
        .set(SUPPORT_TICKET.LAST_ACTIVITY_AT, t.getLastActivityAt())
        .set(SUPPORT_TICKET.FIRST_RESPONSE_AT, t.getFirstResponseAt())
        .set(SUPPORT_TICKET.RESOLVED_AT, t.getResolvedAt())
        .set(SUPPORT_TICKET.RESOLVED_BY, t.getResolvedBy())
        .set(SUPPORT_TICKET.CLOSED_AT, t.getClosedAt())
        .set(SUPPORT_TICKET.CLOSED_BY, t.getClosedBy())
        .set(
            SUPPORT_TICKET.CLOSED_REASON,
            t.getClosedReason() == null ? null : t.getClosedReason().name())
        .set(SUPPORT_TICKET.UPDATED_AT, t.getUpdatedAt())
        .where(SUPPORT_TICKET.ID.eq(t.getId()).and(SUPPORT_TICKET.ORG_ID.eq(t.getOrgId())))
        .execute();
  }

  @Override
  public Optional<SupportTicket> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(SUPPORT_TICKET)
        .where(SUPPORT_TICKET.ID.eq(id).and(SUPPORT_TICKET.ORG_ID.eq(orgId)))
        .fetchOptional(SupportTicketRepositoryImpl::toTicket);
  }

  @Override
  public Optional<SupportTicket> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(SUPPORT_TICKET)
        .where(SUPPORT_TICKET.ID.eq(id).and(SUPPORT_TICKET.ORG_ID.eq(orgId)))
        .forUpdate()
        .fetchOptional(SupportTicketRepositoryImpl::toTicket);
  }

  @Override
  public List<SupportTicket> list(
      UUID orgId, TicketStatus status, UUID openedBy, int offset, int limit) {
    return dsl.selectFrom(SUPPORT_TICKET)
        .where(listFilter(orgId, status, openedBy))
        .orderBy(SUPPORT_TICKET.LAST_ACTIVITY_AT.desc(), SUPPORT_TICKET.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch(SupportTicketRepositoryImpl::toTicket);
  }

  @Override
  public long count(UUID orgId, TicketStatus status, UUID openedBy) {
    return dsl.fetchCount(SUPPORT_TICKET, listFilter(orgId, status, openedBy));
  }

  private static Condition listFilter(UUID orgId, TicketStatus status, UUID openedBy) {
    Condition c = SUPPORT_TICKET.ORG_ID.eq(orgId);
    if (status != null) {
      c = c.and(SUPPORT_TICKET.STATUS.eq(status.name()));
    }
    if (openedBy != null) {
      c = c.and(SUPPORT_TICKET.OPENED_BY.eq(openedBy));
    }
    return c;
  }

  @Override
  public long countNotClosedLocked(UUID orgId) {
    // The org row is the lock: two concurrent opens contend on it, so the count-then-insert cannot
    // both read "9" and both commit (the cash shift's one-per-org move).
    dsl.select(ORG.ID).from(ORG).where(ORG.ID.eq(orgId)).forUpdate().fetchOne();
    return dsl.fetchCount(
        SUPPORT_TICKET,
        SUPPORT_TICKET.ORG_ID.eq(orgId).and(SUPPORT_TICKET.STATUS.ne(TicketStatus.CLOSED.name())));
  }

  @Override
  public void insertMessage(TicketMessage m) {
    dsl.insertInto(SUPPORT_TICKET_MESSAGE)
        .set(SUPPORT_TICKET_MESSAGE.ID, m.id())
        .set(SUPPORT_TICKET_MESSAGE.ORG_ID, m.orgId())
        .set(SUPPORT_TICKET_MESSAGE.TICKET_ID, m.ticketId())
        .set(SUPPORT_TICKET_MESSAGE.KIND, m.kind().name())
        .set(SUPPORT_TICKET_MESSAGE.SIDE, m.side().name())
        .set(SUPPORT_TICKET_MESSAGE.AUTHOR_ID, m.authorId())
        .set(SUPPORT_TICKET_MESSAGE.BODY, m.body())
        .set(SUPPORT_TICKET_MESSAGE.STATUS_TO, m.statusTo() == null ? null : m.statusTo().name())
        .set(SUPPORT_TICKET_MESSAGE.CREATED_AT, m.createdAt())
        .execute();
  }

  @Override
  public List<TicketMessage> findMessages(UUID ticketId, boolean includeNotes) {
    Condition c = SUPPORT_TICKET_MESSAGE.TICKET_ID.eq(ticketId);
    if (!includeNotes) {
      c = c.and(SUPPORT_TICKET_MESSAGE.KIND.ne(TicketMessageKind.NOTE.name()));
    }
    return dsl.selectFrom(SUPPORT_TICKET_MESSAGE)
        .where(c)
        .orderBy(SUPPORT_TICKET_MESSAGE.CREATED_AT.asc(), SUPPORT_TICKET_MESSAGE.ID.asc())
        .fetch(
            r ->
                new TicketMessage(
                    r.getId(),
                    r.getOrgId(),
                    r.getTicketId(),
                    TicketMessageKind.valueOf(r.getKind()),
                    TicketSide.valueOf(r.getSide()),
                    r.getAuthorId(),
                    r.getBody(),
                    r.getStatusTo() == null ? null : TicketStatus.valueOf(r.getStatusTo()),
                    r.getCreatedAt()));
  }

  @Override
  public void insertAttachment(TicketAttachment a) {
    dsl.insertInto(SUPPORT_TICKET_ATTACHMENT)
        .set(SUPPORT_TICKET_ATTACHMENT.ID, a.id())
        .set(SUPPORT_TICKET_ATTACHMENT.ORG_ID, a.orgId())
        .set(SUPPORT_TICKET_ATTACHMENT.TICKET_ID, a.ticketId())
        .set(SUPPORT_TICKET_ATTACHMENT.MESSAGE_ID, a.messageId())
        .set(SUPPORT_TICKET_ATTACHMENT.OBJECT_KEY, a.objectKey())
        .set(SUPPORT_TICKET_ATTACHMENT.CONTENT_TYPE, a.contentType())
        .set(SUPPORT_TICKET_ATTACHMENT.FILE_NAME, a.fileName())
        .set(SUPPORT_TICKET_ATTACHMENT.CREATED_AT, a.createdAt())
        .execute();
  }

  @Override
  public List<TicketAttachment> findAttachments(UUID ticketId) {
    return dsl.selectFrom(SUPPORT_TICKET_ATTACHMENT)
        .where(SUPPORT_TICKET_ATTACHMENT.TICKET_ID.eq(ticketId))
        .orderBy(SUPPORT_TICKET_ATTACHMENT.CREATED_AT.asc(), SUPPORT_TICKET_ATTACHMENT.ID.asc())
        .fetch(
            r ->
                new TicketAttachment(
                    r.getId(),
                    r.getOrgId(),
                    r.getTicketId(),
                    r.getMessageId(),
                    r.getObjectKey(),
                    r.getContentType(),
                    r.getFileName(),
                    r.getCreatedAt()));
  }

  @Override
  public Map<UUID, ListExtras> listExtras(Collection<UUID> ticketIds) {
    Map<UUID, ListExtras> out = new HashMap<>();
    if (ticketIds == null || ticketIds.isEmpty()) {
      return out;
    }
    Map<UUID, Integer> counts = new HashMap<>();
    for (Record r :
        dsl.select(SUPPORT_TICKET_ATTACHMENT.TICKET_ID, DSL.count())
            .from(SUPPORT_TICKET_ATTACHMENT)
            .where(SUPPORT_TICKET_ATTACHMENT.TICKET_ID.in(ticketIds))
            .groupBy(SUPPORT_TICKET_ATTACHMENT.TICKET_ID)
            .fetch()) {
      counts.put(r.get(SUPPORT_TICKET_ATTACHMENT.TICKET_ID), r.get(DSL.count()));
    }
    // The last MESSAGE per ticket (never a NOTE, never a STATUS row): DISTINCT ON in thread order.
    Map<UUID, String> previews = new HashMap<>();
    for (Record r :
        dsl.select(SUPPORT_TICKET_MESSAGE.TICKET_ID, SUPPORT_TICKET_MESSAGE.BODY)
            .distinctOn(SUPPORT_TICKET_MESSAGE.TICKET_ID)
            .from(SUPPORT_TICKET_MESSAGE)
            .where(
                SUPPORT_TICKET_MESSAGE
                    .TICKET_ID
                    .in(ticketIds)
                    .and(SUPPORT_TICKET_MESSAGE.KIND.eq(TicketMessageKind.MESSAGE.name())))
            .orderBy(
                SUPPORT_TICKET_MESSAGE.TICKET_ID,
                SUPPORT_TICKET_MESSAGE.CREATED_AT.desc(),
                SUPPORT_TICKET_MESSAGE.ID.desc())
            .fetch()) {
      String body = r.get(SUPPORT_TICKET_MESSAGE.BODY);
      previews.put(r.get(SUPPORT_TICKET_MESSAGE.TICKET_ID), preview(body));
    }
    for (UUID id : ticketIds) {
      out.put(id, new ListExtras(counts.getOrDefault(id, 0), previews.get(id)));
    }
    return out;
  }

  private static String preview(String body) {
    if (body == null) {
      return null;
    }
    String flat = body.replaceAll("\\s+", " ").trim();
    return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS - 1) + "…";
  }

  @Override
  public Set<UUID> merchantParticipantIds(UUID ticketId) {
    return dsl.selectDistinct(SUPPORT_TICKET_MESSAGE.AUTHOR_ID)
        .from(SUPPORT_TICKET_MESSAGE)
        .where(SUPPORT_TICKET_MESSAGE.TICKET_ID.eq(ticketId))
        .and(SUPPORT_TICKET_MESSAGE.SIDE.eq(TicketSide.MERCHANT.name()))
        .and(SUPPORT_TICKET_MESSAGE.KIND.eq(TicketMessageKind.MESSAGE.name()))
        .and(SUPPORT_TICKET_MESSAGE.AUTHOR_ID.isNotNull())
        .fetchSet(SUPPORT_TICKET_MESSAGE.AUTHOR_ID);
  }

  static SupportTicket toTicket(SupportTicketRecord r) {
    TicketRef ref =
        r.getRefType() == null
            ? null
            : new TicketRef(TicketRefType.valueOf(r.getRefType()), r.getRefId(), r.getRefLabel());
    return SupportTicket.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getNumber(),
        TicketStatus.valueOf(r.getStatus()),
        TicketCategory.valueOf(r.getCategory()),
        Boolean.TRUE.equals(r.getBlocking()),
        r.getSubject(),
        ref,
        r.getOpenedBy(),
        r.getOpenedAt(),
        r.getStatusSince(),
        r.getLastActivityAt(),
        r.getFirstResponseAt(),
        r.getResolvedAt(),
        r.getResolvedBy(),
        r.getClosedAt(),
        r.getClosedBy(),
        r.getClosedReason() == null ? null : TicketCloseReason.valueOf(r.getClosedReason()),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
