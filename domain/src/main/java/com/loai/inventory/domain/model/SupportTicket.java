package com.loai.inventory.domain.model;

import com.loai.inventory.common.exception.InvalidTicketTransitionException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A merchant's ticket to the platform desk ({@code stories/support_tickets.md}).
 *
 * <p><strong>A message is the transition.</strong> The merchant has one verb — a merchant message
 * puts the ticket in {@link TicketStatus#OPEN} from anywhere but {@code CLOSED} (a reply, an answer
 * and a reopen are the same act). A support message puts it in {@link
 * TicketStatus#AWAITING_MERCHANT}, or {@link TicketStatus#RESOLVED} when the desk says so; on a
 * resolved ticket a support footnote changes nothing. {@code CLOSED} is terminal: every write
 * refuses with {@code TICKET_CLOSED}, and the merchant opens a new ticket.
 *
 * <p>Each mutator returns the status <em>entered</em>, or {@code null} when the status did not
 * change — the service turns a non-null answer into a {@code STATUS} row in the thread. {@code
 * statusSince} is stamped on every transition (the desk queue's key); {@code lastActivityAt} on
 * every write (the ledgers' key).
 */
public final class SupportTicket {

  private final UUID id;
  private final UUID orgId;
  private Long number;
  private TicketStatus status;
  private final TicketCategory category;
  private final boolean blocking;
  private final String subject;
  private final TicketRef ref;
  private final UUID openedBy;
  private final OffsetDateTime openedAt;
  private OffsetDateTime statusSince;
  private OffsetDateTime lastActivityAt;
  private OffsetDateTime firstResponseAt;
  private OffsetDateTime resolvedAt;
  private UUID resolvedBy;
  private OffsetDateTime closedAt;
  private UUID closedBy;
  private TicketCloseReason closedReason;
  private final OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private SupportTicket(
      UUID id,
      UUID orgId,
      Long number,
      TicketStatus status,
      TicketCategory category,
      boolean blocking,
      String subject,
      TicketRef ref,
      UUID openedBy,
      OffsetDateTime openedAt,
      OffsetDateTime statusSince,
      OffsetDateTime lastActivityAt,
      OffsetDateTime firstResponseAt,
      OffsetDateTime resolvedAt,
      UUID resolvedBy,
      OffsetDateTime closedAt,
      UUID closedBy,
      TicketCloseReason closedReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.number = number;
    this.status = status;
    this.category = category;
    this.blocking = blocking;
    this.subject = subject;
    this.ref = ref;
    this.openedBy = openedBy;
    this.openedAt = openedAt;
    this.statusSince = statusSince;
    this.lastActivityAt = lastActivityAt;
    this.firstResponseAt = firstResponseAt;
    this.resolvedAt = resolvedAt;
    this.resolvedBy = resolvedBy;
    this.closedAt = closedAt;
    this.closedBy = closedBy;
    this.closedReason = closedReason;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /** A fresh ticket: {@code OPEN}, the desk's, waiting since now. The number comes from the DB. */
  public static SupportTicket open(
      UUID id,
      UUID orgId,
      UUID openedBy,
      TicketCategory category,
      String subject,
      boolean blocking,
      TicketRef ref,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(openedBy, "openedBy required");
    Objects.requireNonNull(category, "category required");
    Objects.requireNonNull(now, "now required");
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject required");
    }
    return new SupportTicket(
        id,
        orgId,
        null,
        TicketStatus.OPEN,
        category,
        blocking,
        subject,
        ref,
        openedBy,
        now,
        now,
        now,
        null,
        null,
        null,
        null,
        null,
        null,
        now,
        now);
  }

  /** Rebuild from storage; every column, no validation. */
  public static SupportTicket rehydrate(
      UUID id,
      UUID orgId,
      Long number,
      TicketStatus status,
      TicketCategory category,
      boolean blocking,
      String subject,
      TicketRef ref,
      UUID openedBy,
      OffsetDateTime openedAt,
      OffsetDateTime statusSince,
      OffsetDateTime lastActivityAt,
      OffsetDateTime firstResponseAt,
      OffsetDateTime resolvedAt,
      UUID resolvedBy,
      OffsetDateTime closedAt,
      UUID closedBy,
      TicketCloseReason closedReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    return new SupportTicket(
        id,
        orgId,
        number,
        status,
        category,
        blocking,
        subject,
        ref,
        openedBy,
        openedAt,
        statusSince,
        lastActivityAt,
        firstResponseAt,
        resolvedAt,
        resolvedBy,
        closedAt,
        closedBy,
        closedReason,
        createdAt,
        updatedAt);
  }

  // Transitions

  /**
   * The merchant wrote: the ticket is the desk's again. From {@code AWAITING_MERCHANT} or {@code
   * RESOLVED} this enters {@code OPEN} (the latter is the reopen, and clears the resolution); on an
   * {@code OPEN} ticket it only touches the clock. {@code CLOSED} refuses.
   */
  public TicketStatus merchantMessage(OffsetDateTime now) {
    requireNotClosed();
    touch(now);
    if (status == TicketStatus.OPEN) {
      return null;
    }
    resolvedAt = null;
    resolvedBy = null;
    return enter(TicketStatus.OPEN, now);
  }

  /**
   * The desk wrote: the ticket is the merchant's — {@code AWAITING_MERCHANT}, or {@code RESOLVED}
   * when {@code resolve} is set. A message on a {@code RESOLVED} ticket leaves it resolved (a
   * footnote is not a reopen). The first support message stamps {@code firstResponseAt}. {@code
   * CLOSED} refuses.
   */
  public TicketStatus supportMessage(UUID actor, boolean resolve, OffsetDateTime now) {
    requireNotClosed();
    touch(now);
    if (firstResponseAt == null) {
      firstResponseAt = now;
    }
    if (status == TicketStatus.RESOLVED) {
      return null;
    }
    if (resolve) {
      resolvedAt = now;
      resolvedBy = actor;
      return enter(TicketStatus.RESOLVED, now);
    }
    if (status == TicketStatus.AWAITING_MERCHANT) {
      return null;
    }
    return enter(TicketStatus.AWAITING_MERCHANT, now);
  }

  /** The desk's resolve without words: {@code OPEN | AWAITING_MERCHANT → RESOLVED}. */
  public TicketStatus resolve(UUID actor, OffsetDateTime now) {
    requireNotClosed();
    if (status == TicketStatus.RESOLVED) {
      throw InvalidTicketTransitionException.refused(status, "resolve");
    }
    touch(now);
    resolvedAt = now;
    resolvedBy = actor;
    return enter(TicketStatus.RESOLVED, now);
  }

  /** Close from any status but {@code CLOSED}; {@code actor} is null for the automatic close. */
  public TicketStatus close(UUID actor, TicketCloseReason reason, OffsetDateTime now) {
    requireNotClosed();
    Objects.requireNonNull(reason, "reason required");
    touch(now);
    closedAt = now;
    closedBy = actor;
    closedReason = reason;
    return enter(TicketStatus.CLOSED, now);
  }

  private TicketStatus enter(TicketStatus next, OffsetDateTime now) {
    status = next;
    statusSince = now;
    return next;
  }

  private void touch(OffsetDateTime now) {
    lastActivityAt = now;
    updatedAt = now;
  }

  private void requireNotClosed() {
    if (status == TicketStatus.CLOSED) {
      throw InvalidTicketTransitionException.closed(number);
    }
  }

  // Accessors

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public Long getNumber() {
    return number;
  }

  /** Set once, by the repository, from the sequence the insert returned. */
  public void assignNumber(long number) {
    if (this.number != null) {
      throw new IllegalStateException("number already assigned");
    }
    this.number = number;
  }

  public TicketStatus getStatus() {
    return status;
  }

  public TicketCategory getCategory() {
    return category;
  }

  public boolean isBlocking() {
    return blocking;
  }

  public String getSubject() {
    return subject;
  }

  public TicketRef getRef() {
    return ref;
  }

  public UUID getOpenedBy() {
    return openedBy;
  }

  public OffsetDateTime getOpenedAt() {
    return openedAt;
  }

  public OffsetDateTime getStatusSince() {
    return statusSince;
  }

  public OffsetDateTime getLastActivityAt() {
    return lastActivityAt;
  }

  public OffsetDateTime getFirstResponseAt() {
    return firstResponseAt;
  }

  public OffsetDateTime getResolvedAt() {
    return resolvedAt;
  }

  public UUID getResolvedBy() {
    return resolvedBy;
  }

  public OffsetDateTime getClosedAt() {
    return closedAt;
  }

  public UUID getClosedBy() {
    return closedBy;
  }

  public TicketCloseReason getClosedReason() {
    return closedReason;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
