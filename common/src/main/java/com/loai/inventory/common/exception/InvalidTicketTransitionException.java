package com.loai.inventory.common.exception;

/**
 * A write a {@code SupportTicket} refuses ({@code stories/support_tickets.md}): anything on a
 * {@code CLOSED} ticket ({@link #KIND_CLOSED} — the client hides the composer and offers a new
 * ticket), or a verb the current status does not take ({@link #KIND_TRANSITION}). A 409 whose
 * envelope carries the kind, the {@code ShiftRequiredException} way.
 */
public class InvalidTicketTransitionException extends ConflictException {

  public static final String KIND_CLOSED = "TICKET_CLOSED";
  public static final String KIND_TRANSITION = "TICKET_TRANSITION";

  private final String kind;

  private InvalidTicketTransitionException(String kind, String message) {
    super(message);
    this.kind = kind;
  }

  public static InvalidTicketTransitionException closed(Long number) {
    return new InvalidTicketTransitionException(
        KIND_CLOSED,
        "ticket "
            + (number == null ? "" : "#" + number + " ")
            + "is closed — open a new ticket to continue");
  }

  public static InvalidTicketTransitionException refused(Enum<?> from, String verb) {
    return new InvalidTicketTransitionException(
        KIND_TRANSITION, "cannot " + verb + " a ticket that is " + from);
  }

  public String getKind() {
    return kind;
  }
}
