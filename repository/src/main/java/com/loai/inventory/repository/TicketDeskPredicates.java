package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET;

import com.loai.inventory.domain.model.TicketStatus;
import org.jooq.Condition;
import org.jooq.impl.DSL;

/**
 * <strong>The single definition of what the desk counts and lists.</strong> Consumed by {@link
 * PlatformStatsRepositoryImpl#ticketCounts()} (the overview tile) and {@link
 * PlatformTicketRepositoryImpl} (the tab badges and the rows), and restated by neither — the {@link
 * PlatformQueuePredicates} move, so a tile and its inbox are the same number by construction.
 */
final class TicketDeskPredicates {

  private TicketDeskPredicates() {}

  /** Every ticket in {@code status}; {@code null} = every ticket. */
  static Condition where(TicketStatus status) {
    return status == null ? DSL.noCondition() : SUPPORT_TICKET.STATUS.eq(status.name());
  }

  /** The open tickets whose merchant says they cannot sell — the overview's sub-line. */
  static Condition blockingOpen() {
    return where(TicketStatus.OPEN).and(SUPPORT_TICKET.BLOCKING.isTrue());
  }
}
