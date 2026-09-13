package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of a ticket's thread. {@code authorId} is null only for an automatic {@code STATUS} row;
 * {@code body} is null for every {@code STATUS} row; {@code statusTo} is set only on them.
 */
public record TicketMessage(
    UUID id,
    UUID orgId,
    UUID ticketId,
    TicketMessageKind kind,
    TicketSide side,
    UUID authorId,
    String body,
    TicketStatus statusTo,
    OffsetDateTime createdAt) {

  public static TicketMessage message(
      UUID id,
      UUID orgId,
      UUID ticketId,
      TicketSide side,
      UUID authorId,
      String body,
      OffsetDateTime now) {
    return new TicketMessage(
        id, orgId, ticketId, TicketMessageKind.MESSAGE, side, authorId, body, null, now);
  }

  public static TicketMessage status(
      UUID id,
      UUID orgId,
      UUID ticketId,
      TicketSide side,
      UUID authorId,
      TicketStatus statusTo,
      OffsetDateTime now) {
    return new TicketMessage(
        id, orgId, ticketId, TicketMessageKind.STATUS, side, authorId, null, statusTo, now);
  }
}
