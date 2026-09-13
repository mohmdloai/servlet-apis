package com.loai.inventory.api.job;

import com.loai.inventory.service.SupportTicketService;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recurring JobRunr job that closes {@code RESOLVED} support tickets the merchant never answered
 * ({@code stories/support_ticket_reach.md}) — same design as {@link UnverifiedAccountPurgeJob}: a
 * poller over a state column, a bounded batch, each ticket in its own transaction. The thread gets
 * a {@code STATUS} row with no author and {@code closed_reason = AUTO}; nobody is notified — a
 * merchant who did not answer a resolution in seven days is not waiting for news of it.
 */
public final class SupportTicketAutoCloseJob {

  private static final Logger log = LoggerFactory.getLogger(SupportTicketAutoCloseJob.class);

  private final SupportTicketService tickets;
  private final int days;
  private final int batchLimit;

  public SupportTicketAutoCloseJob(SupportTicketService tickets, int days, int batchLimit) {
    this.tickets = tickets;
    this.days = days;
    this.batchLimit = batchLimit;
  }

  @Job(name = "support-ticket-auto-close")
  public void run() {
    int closed = tickets.autoClose(SupportTicketService.ticketClock(), days, batchLimit);
    if (closed > 0) {
      log.info("Support-ticket auto-close tick: {} closed after {} days", closed, days);
    }
  }
}
