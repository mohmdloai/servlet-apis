package com.loai.inventory.api.support;

import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET_MESSAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.TicketCloseReason;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.service.SupportTicketService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The auto-close sweep ({@code stories/support_ticket_reach.md}): a {@code RESOLVED} ticket eight
 * days on becomes {@code CLOSED} with {@code closed_reason = AUTO}, no {@code closed_by}, and a
 * {@code STATUS} row with no author; one six days on is untouched, an {@code OPEN} one is
 * untouched, and no notification is written. Bounded by the batch; idempotent on the next tick.
 */
class SupportTicketAutoCloseIT extends SupportReachItBase {

  @Test
  void closesOnlyResolvedTicketsPastTheWindow_writesTheThread_tellsNobody() {
    OffsetDateTime now = SupportTicketService.ticketClock();
    UUID stale = resolvedTicket("Cannot invite a manager", now.minusDays(8));
    UUID fresh = resolvedTicket("Printer stops", now.minusDays(6));
    UUID open = tickets.open(org, staff, cmd("Old price shown", "Sugar 1kg.")).ticket().getId();
    long notificationsBefore = dsl.fetchCount(NOTIFICATION);
    long staleMessagesBefore =
        dsl.fetchCount(SUPPORT_TICKET_MESSAGE, SUPPORT_TICKET_MESSAGE.TICKET_ID.eq(stale));

    assertEquals(1, tickets.autoClose(now, 7, 100));

    SupportTicket closed = load(stale);
    assertEquals(TicketStatus.CLOSED, closed.getStatus());
    assertEquals(TicketCloseReason.AUTO, closed.getClosedReason());
    assertNull(closed.getClosedBy(), "nobody closed it");
    assertEquals(now.toInstant(), closed.getClosedAt().toInstant());
    assertEquals(now.toInstant(), closed.getStatusSince().toInstant());
    var statusRow =
        dsl.selectFrom(SUPPORT_TICKET_MESSAGE)
            .where(SUPPORT_TICKET_MESSAGE.TICKET_ID.eq(stale))
            .and(SUPPORT_TICKET_MESSAGE.KIND.eq("STATUS"))
            .and(SUPPORT_TICKET_MESSAGE.STATUS_TO.eq("CLOSED"))
            .fetchOne();
    assertEquals(
        1,
        dsl.fetchCount(SUPPORT_TICKET_MESSAGE, SUPPORT_TICKET_MESSAGE.TICKET_ID.eq(stale))
            - staleMessagesBefore);
    assertNull(
        statusRow.getAuthorId(), "the line has no author — the thread says it was automatic");
    assertEquals("SUPPORT", statusRow.getSide());

    assertEquals(TicketStatus.RESOLVED, load(fresh).getStatus(), "six days is inside the window");
    assertEquals(TicketStatus.OPEN, load(open).getStatus());
    assertEquals(notificationsBefore, dsl.fetchCount(NOTIFICATION), "no push, no in-app row");

    // The merchant's view names the reason, and the next tick finds nothing.
    assertEquals(
        TicketCloseReason.AUTO, tickets.get(org, staff, false, stale).ticket().getClosedReason());
    assertEquals(0, tickets.autoClose(now, 7, 100));
  }

  @Test
  void theBatchBoundsOneTick_andTheRestGoOnTheNext() {
    OffsetDateTime now = SupportTicketService.ticketClock();
    resolvedTicket("A", now.minusDays(9));
    resolvedTicket("B", now.minusDays(8));
    assertEquals(1, tickets.autoClose(now, 7, 1));
    assertEquals(
        1,
        dsl.fetchCount(SUPPORT_TICKET, SUPPORT_TICKET.STATUS.eq("CLOSED")),
        "oldest resolution first");
    assertEquals(1, tickets.autoClose(now, 7, 1));
    assertEquals(0, tickets.autoClose(now, 7, 1));
  }

  private UUID resolvedTicket(String subject, OffsetDateTime resolvedAt) {
    UUID id = tickets.open(org, staff, cmd(subject, "…")).ticket().getId();
    desk.resolve(platform(admin, SystemRole.ADMIN), env(), id);
    dsl.update(SUPPORT_TICKET)
        .set(SUPPORT_TICKET.RESOLVED_AT, resolvedAt)
        .where(SUPPORT_TICKET.ID.eq(id))
        .execute();
    return id;
  }

  private SupportTicket load(UUID id) {
    return tickets.get(org, staff, true, id).ticket();
  }
}
