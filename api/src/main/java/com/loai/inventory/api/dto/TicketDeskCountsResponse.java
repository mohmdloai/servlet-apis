package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.TicketDeskCounts;

/**
 * {@code GET /api/admin/tickets/counts} — the tab badges; the overview tile reads the same
 * predicate.
 */
public record TicketDeskCountsResponse(
    long open, long awaitingMerchant, long resolved, long closed, long blockingOpen) {
  public static TicketDeskCountsResponse from(TicketDeskCounts c) {
    return new TicketDeskCountsResponse(
        c.open(), c.awaitingMerchant(), c.resolved(), c.closed(), c.blockingOpen());
  }
}
