package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.TicketAttachment;
import com.loai.inventory.domain.model.TicketMessage;
import com.loai.inventory.domain.model.TicketStatus;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The org-scoped ticket store ({@code stories/support_tickets.md}). Every read and write takes the
 * org first; the desk reaches a ticket through this same repository once {@link
 * PlatformTicketRepository#findOrgId} has named its tenant.
 */
public interface SupportTicketRepository {

  /** Insert and assign the sequence number the database allocated. */
  void insert(SupportTicket ticket);

  void update(SupportTicket ticket);

  Optional<SupportTicket> findById(UUID orgId, UUID id);

  /** Row-locked for the caller's transaction — every transition reads through this. */
  Optional<SupportTicket> findByIdForUpdate(UUID orgId, UUID id);

  /**
   * The org's ledger, newest activity first. {@code status} null = every status; {@code openedBy}
   * null = every opener (STAFF callers pass their own id).
   */
  List<SupportTicket> list(UUID orgId, TicketStatus status, UUID openedBy, int offset, int limit);

  long count(UUID orgId, TicketStatus status, UUID openedBy);

  /**
   * How many of the org's tickets are not {@code CLOSED}, with the org row locked so two phones
   * cannot race past the cap.
   */
  long countNotClosedLocked(UUID orgId);

  void insertMessage(TicketMessage message);

  /** The thread in order; {@code includeNotes} is false on the merchant plane. */
  List<TicketMessage> findMessages(UUID ticketId, boolean includeNotes);

  void insertAttachment(TicketAttachment attachment);

  List<TicketAttachment> findAttachments(UUID ticketId);

  /** Per-ticket extras a list needs without a signed URL: attachment count + last preview. */
  Map<UUID, ListExtras> listExtras(Collection<UUID> ticketIds);

  /** Distinct authors of the ticket's {@code MERCHANT}-side {@code MESSAGE} rows. */
  Set<UUID> merchantParticipantIds(UUID ticketId);

  /**
   * The auto-close sweep ({@code stories/support_ticket_reach.md}) — the one org-agnostic read on
   * this repository, because the sweeper serves every tenant at once: {@code RESOLVED} tickets
   * whose {@code resolved_at} is before {@code cutoff}, oldest first, at most {@code limit} ids.
   */
  List<UUID> findAutoCloseCandidates(OffsetDateTime cutoff, int limit);

  /**
   * Lock one candidate for the sweep ({@code FOR UPDATE SKIP LOCKED}), re-checking the predicate so
   * a ticket the merchant reopened between the read and the lock is left alone. Empty = skip it.
   */
  Optional<SupportTicket> lockAutoCloseCandidate(UUID id, OffsetDateTime cutoff);

  record ListExtras(int attachmentCount, String lastMessagePreview) {}
}
