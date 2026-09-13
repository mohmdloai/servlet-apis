package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET;

import com.loai.inventory.domain.model.DeskTicketRow;
import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.PlatformQueueOrg;
import com.loai.inventory.domain.model.TicketCategory;
import com.loai.inventory.domain.model.TicketDeskCounts;
import com.loai.inventory.domain.model.TicketRef;
import com.loai.inventory.domain.model.TicketRefType;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.domain.repository.PlatformTicketRepository;
import com.loai.inventory.domain.repository.SupportTicketRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The desk's cross-org rows and counts. See {@link PlatformTicketRepository} for the three rules
 * that keep this un-scoped read safe and {@link TicketDeskPredicates} for why no membership rule is
 * written here. <strong>Every SELECT lists its columns</strong> — the whitelist is the point.
 */
public final class PlatformTicketRepositoryImpl implements PlatformTicketRepository {

  private final DSLContext dsl;

  public PlatformTicketRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<DeskTicketRow> page(TicketStatus status, UUID orgId, int offset, int limit) {
    List<Record> rows =
        dsl.select(
                SUPPORT_TICKET.ID,
                SUPPORT_TICKET.NUMBER,
                SUPPORT_TICKET.STATUS,
                SUPPORT_TICKET.CATEGORY,
                SUPPORT_TICKET.BLOCKING,
                SUPPORT_TICKET.SUBJECT,
                SUPPORT_TICKET.REF_TYPE,
                SUPPORT_TICKET.REF_ID,
                SUPPORT_TICKET.REF_LABEL,
                SUPPORT_TICKET.OPENED_BY,
                SUPPORT_TICKET.OPENED_AT,
                SUPPORT_TICKET.STATUS_SINCE,
                SUPPORT_TICKET.LAST_ACTIVITY_AT,
                ORG.ID,
                ORG.NAME,
                ORG.SLUG,
                ORG.ACTIVE,
                ORG.SUSPENDED_AT,
                APP_USER.DISPLAY_NAME)
            .from(SUPPORT_TICKET)
            .join(ORG)
            .on(ORG.ID.eq(SUPPORT_TICKET.ORG_ID))
            .join(APP_USER)
            .on(APP_USER.ID.eq(SUPPORT_TICKET.OPENED_BY))
            .where(filter(status, orgId))
            .orderBy(order(status))
            .offset(offset)
            .limit(limit)
            .fetch(r -> (Record) r);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, SupportTicketRepository.ListExtras> extras =
        new SupportTicketRepositoryImpl(dsl)
            .listExtras(rows.stream().map(r -> r.get(SUPPORT_TICKET.ID)).toList());
    return rows.stream().map(r -> toRow(r, extras)).toList();
  }

  @Override
  public long count(TicketStatus status, UUID orgId) {
    return dsl.fetchCount(SUPPORT_TICKET, filter(status, orgId));
  }

  @Override
  public TicketDeskCounts counts(UUID orgId) {
    Condition orgCond = orgId == null ? DSL.noCondition() : SUPPORT_TICKET.ORG_ID.eq(orgId);
    return new TicketDeskCounts(
        dsl.fetchCount(SUPPORT_TICKET, TicketDeskPredicates.where(TicketStatus.OPEN).and(orgCond)),
        dsl.fetchCount(
            SUPPORT_TICKET,
            TicketDeskPredicates.where(TicketStatus.AWAITING_MERCHANT).and(orgCond)),
        dsl.fetchCount(
            SUPPORT_TICKET, TicketDeskPredicates.where(TicketStatus.RESOLVED).and(orgCond)),
        dsl.fetchCount(
            SUPPORT_TICKET, TicketDeskPredicates.where(TicketStatus.CLOSED).and(orgCond)),
        dsl.fetchCount(SUPPORT_TICKET, TicketDeskPredicates.blockingOpen().and(orgCond)));
  }

  @Override
  public Optional<UUID> findOrgId(UUID ticketId) {
    return dsl.select(SUPPORT_TICKET.ORG_ID)
        .from(SUPPORT_TICKET)
        .where(SUPPORT_TICKET.ID.eq(ticketId))
        .fetchOptional(SUPPORT_TICKET.ORG_ID);
  }

  private static Condition filter(TicketStatus status, UUID orgId) {
    Condition c = TicketDeskPredicates.where(status);
    return orgId == null ? c : c.and(SUPPORT_TICKET.ORG_ID.eq(orgId));
  }

  /**
   * {@code OPEN} is a queue — the merchant who cannot sell first, then whoever has waited longest;
   * everything else is a ledger, newest activity first. Both tie-break on id.
   */
  private static OrderField<?>[] order(TicketStatus status) {
    if (status == TicketStatus.OPEN) {
      return new OrderField<?>[] {
        SUPPORT_TICKET.BLOCKING.desc(), SUPPORT_TICKET.STATUS_SINCE.asc(), SUPPORT_TICKET.ID.asc()
      };
    }
    return new OrderField<?>[] {SUPPORT_TICKET.LAST_ACTIVITY_AT.desc(), SUPPORT_TICKET.ID.desc()};
  }

  private static DeskTicketRow toRow(
      Record r, Map<UUID, SupportTicketRepository.ListExtras> extras) {
    UUID id = r.get(SUPPORT_TICKET.ID);
    SupportTicketRepository.ListExtras x =
        extras.getOrDefault(id, new SupportTicketRepository.ListExtras(0, null));
    TicketRef ref =
        r.get(SUPPORT_TICKET.REF_TYPE) == null
            ? null
            : new TicketRef(
                TicketRefType.valueOf(r.get(SUPPORT_TICKET.REF_TYPE)),
                r.get(SUPPORT_TICKET.REF_ID),
                r.get(SUPPORT_TICKET.REF_LABEL));
    return new DeskTicketRow(
        id,
        r.get(SUPPORT_TICKET.NUMBER),
        TicketStatus.valueOf(r.get(SUPPORT_TICKET.STATUS)),
        TicketCategory.valueOf(r.get(SUPPORT_TICKET.CATEGORY)),
        Boolean.TRUE.equals(r.get(SUPPORT_TICKET.BLOCKING)),
        r.get(SUPPORT_TICKET.SUBJECT),
        ref,
        new PlatformQueueOrg(
            r.get(ORG.ID),
            r.get(ORG.NAME),
            r.get(ORG.SLUG),
            OrgStatus.of(Boolean.TRUE.equals(r.get(ORG.ACTIVE)), r.get(ORG.SUSPENDED_AT))),
        r.get(SUPPORT_TICKET.OPENED_BY),
        r.get(APP_USER.DISPLAY_NAME),
        r.get(SUPPORT_TICKET.OPENED_AT),
        r.get(SUPPORT_TICKET.STATUS_SINCE),
        r.get(SUPPORT_TICKET.LAST_ACTIVITY_AT),
        x.attachmentCount(),
        x.lastMessagePreview());
  }
}
