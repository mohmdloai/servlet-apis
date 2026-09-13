package com.loai.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.InvalidTicketTransitionException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The aggregate's transition table ({@code stories/support_tickets.md} §A message is the
 * transition), every cell: who may move a ticket where, what each move stamps, and that {@code
 * CLOSED} refuses everything with the {@code TICKET_CLOSED} kind.
 */
class SupportTicketMachineTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID MERCHANT = UUID.randomUUID();
  private static final UUID DESK = UUID.randomUUID();
  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 9, 13, 10, 0, 0, 0, ZoneOffset.UTC);

  private static SupportTicket open() {
    return SupportTicket.open(
        UUID.randomUUID(), ORG, MERCHANT, TicketCategory.DEVICES, "Printer stops", true, null, T0);
  }

  @Test
  void open_isTheDesks_waitingSinceNow() {
    SupportTicket t = open();
    assertEquals(TicketStatus.OPEN, t.getStatus());
    assertEquals(T0, t.getStatusSince());
    assertEquals(T0, t.getLastActivityAt());
    assertNull(t.getNumber(), "the number comes from the database");
  }

  @Test
  void merchantMessage_onOpen_changesNothingButTheClock() {
    SupportTicket t = open();
    OffsetDateTime t1 = T0.plusMinutes(5);
    assertNull(t.merchantMessage(t1));
    assertEquals(TicketStatus.OPEN, t.getStatus());
    assertEquals(
        T0,
        t.getStatusSince(),
        "the desk has held it since T0; a second message does not reset that");
    assertEquals(t1, t.getLastActivityAt());
  }

  @Test
  void supportMessage_handsItToTheMerchant_andStampsFirstResponseOnce() {
    SupportTicket t = open();
    OffsetDateTime t1 = T0.plusHours(1);
    assertEquals(TicketStatus.AWAITING_MERCHANT, t.supportMessage(DESK, false, t1));
    assertEquals(t1, t.getStatusSince());
    assertEquals(t1, t.getFirstResponseAt());

    OffsetDateTime t2 = T0.plusHours(2);
    assertNull(t.supportMessage(DESK, false, t2), "a second support message changes no status");
    assertEquals(t1, t.getFirstResponseAt(), "first response is stamped once");
    assertEquals(t1, t.getStatusSince());
    assertEquals(t2, t.getLastActivityAt());
  }

  @Test
  void merchantMessage_onAwaiting_reopensTheDesksClock() {
    SupportTicket t = open();
    t.supportMessage(DESK, false, T0.plusHours(1));
    OffsetDateTime t2 = T0.plusHours(3);
    assertEquals(TicketStatus.OPEN, t.merchantMessage(t2));
    assertEquals(
        t2, t.getStatusSince(), "waiting-since restarts when the ball returns to the desk");
  }

  @Test
  void supportMessageWithResolve_resolves_andAFootnoteKeepsItResolved() {
    SupportTicket t = open();
    OffsetDateTime t1 = T0.plusHours(1);
    assertEquals(TicketStatus.RESOLVED, t.supportMessage(DESK, true, t1));
    assertEquals(t1, t.getResolvedAt());
    assertEquals(DESK, t.getResolvedBy());

    assertNull(t.supportMessage(DESK, false, T0.plusHours(2)), "a footnote is not a reopen");
    assertEquals(TicketStatus.RESOLVED, t.getStatus());
    assertNull(
        t.supportMessage(DESK, true, T0.plusHours(3)), "resolving twice is a no-op, not an error");
  }

  @Test
  void merchantMessage_onResolved_isTheReopen_andClearsTheResolution() {
    SupportTicket t = open();
    t.supportMessage(DESK, true, T0.plusHours(1));
    assertEquals(TicketStatus.OPEN, t.merchantMessage(T0.plusHours(2)));
    assertNull(t.getResolvedAt());
    assertNull(t.getResolvedBy());
  }

  @Test
  void resolve_withoutWords_fromOpenOrAwaiting_butNotTwice() {
    SupportTicket t = open();
    assertEquals(TicketStatus.RESOLVED, t.resolve(DESK, T0.plusHours(1)));
    InvalidTicketTransitionException e =
        assertThrows(
            InvalidTicketTransitionException.class, () -> t.resolve(DESK, T0.plusHours(2)));
    assertEquals(InvalidTicketTransitionException.KIND_TRANSITION, e.getKind());

    SupportTicket u = open();
    u.supportMessage(DESK, false, T0.plusHours(1));
    assertEquals(TicketStatus.RESOLVED, u.resolve(DESK, T0.plusHours(2)));
  }

  @Test
  void close_fromAnywhereButClosed_thenEverythingRefusesWithTheClosedKind() {
    for (TicketStatus from :
        new TicketStatus[] {
          TicketStatus.OPEN, TicketStatus.AWAITING_MERCHANT, TicketStatus.RESOLVED
        }) {
      SupportTicket t = open();
      if (from == TicketStatus.AWAITING_MERCHANT) {
        t.supportMessage(DESK, false, T0.plusMinutes(1));
      } else if (from == TicketStatus.RESOLVED) {
        t.supportMessage(DESK, true, T0.plusMinutes(1));
      }
      assertEquals(from, t.getStatus());
      OffsetDateTime tc = T0.plusHours(5);
      assertEquals(TicketStatus.CLOSED, t.close(MERCHANT, TicketCloseReason.MERCHANT, tc));
      assertEquals(tc, t.getClosedAt());
      assertEquals(TicketCloseReason.MERCHANT, t.getClosedReason());
      assertEquals(MERCHANT, t.getClosedBy());

      for (Runnable write :
          new Runnable[] {
            () -> t.merchantMessage(tc.plusMinutes(1)),
            () -> t.supportMessage(DESK, false, tc.plusMinutes(1)),
            () -> t.supportMessage(DESK, true, tc.plusMinutes(1)),
            () -> t.resolve(DESK, tc.plusMinutes(1)),
            () -> t.close(DESK, TicketCloseReason.SUPPORT, tc.plusMinutes(1))
          }) {
        InvalidTicketTransitionException e =
            assertThrows(InvalidTicketTransitionException.class, write::run);
        assertEquals(InvalidTicketTransitionException.KIND_CLOSED, e.getKind());
      }
      assertEquals(tc, t.getLastActivityAt(), "a refused write touches nothing");
    }
  }

  @Test
  void autoClose_hasNoActor() {
    SupportTicket t = open();
    t.supportMessage(DESK, true, T0.plusHours(1));
    assertEquals(TicketStatus.CLOSED, t.close(null, TicketCloseReason.AUTO, T0.plusDays(7)));
    assertNull(t.getClosedBy());
    assertNotNull(t.getClosedAt());
  }

  @Test
  void number_isAssignedOnce() {
    SupportTicket t = open();
    t.assignNumber(1042);
    assertEquals(1042L, t.getNumber());
    assertThrows(IllegalStateException.class, () -> t.assignNumber(1043));
  }

  @Test
  void statusWire_roundTrips_andRefusesUnknown() {
    for (TicketStatus s : TicketStatus.values()) {
      assertEquals(s, TicketStatus.fromWire(s.wire()));
    }
    assertNull(TicketStatus.fromWire(null));
    assertNull(TicketStatus.fromWire("  "));
    assertThrows(IllegalArgumentException.class, () -> TicketStatus.fromWire("pending"));
  }
}
