package com.loai.inventory.domain.model;

/**
 * The three kinds of row in a ticket's thread: a {@code MESSAGE} either side reads, a {@code NOTE}
 * only the desk reads (written from slice 3; excluded from the merchant read from day one), and a
 * {@code STATUS} row recording a transition — the history is the thread, not a second table.
 */
public enum TicketMessageKind {
  MESSAGE,
  NOTE,
  STATUS
}
