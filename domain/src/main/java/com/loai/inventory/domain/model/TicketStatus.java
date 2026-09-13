package com.loai.inventory.domain.model;

import java.util.Locale;

/**
 * Where a {@link SupportTicket} is and who holds it ({@code stories/support_tickets.md}). One owner
 * per status: {@code OPEN} is the desk's to answer, {@code AWAITING_MERCHANT} and {@code RESOLVED}
 * are the merchant's (reply, or confirm), {@code CLOSED} is nobody's. A message is the transition —
 * see {@link SupportTicket}. Stored as {@code name()} in an open TEXT column; the order here is the
 * only order there is.
 */
public enum TicketStatus {
  OPEN,
  AWAITING_MERCHANT,
  RESOLVED,
  CLOSED;

  /** The lowercase wire form used by {@code ?status=}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parse a {@code ?status=} value. {@code null}/blank means "no filter"; an unknown value throws
   * (the caller maps it to a 400 naming all four).
   */
  public static TicketStatus fromWire(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    for (TicketStatus s : values()) {
      if (s.wire().equals(raw.trim().toLowerCase(Locale.ROOT))) {
        return s;
      }
    }
    throw new IllegalArgumentException("Unknown ticket status: " + raw);
  }

  public static String allWireValues() {
    StringBuilder sb = new StringBuilder();
    for (TicketStatus s : values()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(s.wire());
    }
    return sb.toString();
  }
}
