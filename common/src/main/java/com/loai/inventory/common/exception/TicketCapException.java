package com.loai.inventory.common.exception;

/**
 * The org already holds the maximum number of not-closed support tickets ({@code
 * stories/support_tickets.md} §The cap). A 409 whose envelope carries {@link #KIND}, so the client
 * says "close one to open another" and links the list. Nothing is written.
 */
public class TicketCapException extends ConflictException {

  public static final String KIND = "TICKET_CAP";

  public TicketCapException(int cap) {
    super("this org already has " + cap + " open tickets — close one before opening another");
  }
}
