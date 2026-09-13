package com.loai.inventory.api.support;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.service.SupportTicketService;
import com.loai.inventory.service.SupportTicketService.TicketView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The suspended tenant's door ({@code stories/support_ticket_reach.md}) at the service seam: the
 * ticket services never consult the org's status, so a suspended org's STAFF member opens a ticket,
 * reads the thread and posts to it — and the desk sees the ticket wearing the tenant's {@code
 * suspended} status. The gate half (every other route → 403 {@code ORG_SUSPENDED}, support
 * admitted, an outsider still generic) is {@code OrgSuspensionEnforcementTest} and the handler case
 * in {@link SupportTicketHandlerTest}.
 */
class SuspendedDoorIT extends SupportReachItBase {

  @Test
  void aSuspendedOrgsMemberOpensReadsAndPosts_andTheDeskSeesTheTenantSuspended() {
    dsl.update(ORG)
        .set(ORG.ACTIVE, false)
        .set(ORG.SUSPENDED_AT, SupportTicketService.ticketClock())
        .set(ORG.SUSPENDED_REASON, "Repeated chargebacks pending investigation")
        .where(ORG.ID.eq(org))
        .execute();

    TicketView opened =
        tickets.open(org, staff, cmd("We were suspended this morning", "About the suspension."));
    UUID id = opened.ticket().getId();
    assertEquals(TicketStatus.OPEN, opened.ticket().getStatus());

    TicketView read = tickets.get(org, staff, false, id);
    assertEquals(1, read.messages().size());

    desk.reply(
        platform(admin, SystemRole.ADMIN), env(), id, "Looking into it now.", List.of(), false);
    TicketView posted = tickets.post(org, staff, false, id, "Thank you — waiting.", List.of());
    assertEquals(TicketStatus.OPEN, posted.ticket().getStatus(), "the merchant's message reopens");
    assertEquals(
        3,
        posted.messages().stream()
            .filter(m -> m.message().kind().name().equals("MESSAGE"))
            .count());

    var deskView = desk.get(id);
    assertEquals(
        OrgStatus.SUSPENDED, deskView.org().status(), "the ticket may be about exactly that");
    assertEquals("Mart Cairo", deskView.org().name());
  }
}
