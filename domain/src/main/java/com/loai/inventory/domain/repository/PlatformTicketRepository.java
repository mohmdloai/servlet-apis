package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.DeskTicketRow;
import com.loai.inventory.domain.model.TicketDeskCounts;
import com.loai.inventory.domain.model.TicketStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The desk's cross-org ticket reads behind {@code GET /api/admin/tickets} — the <strong>fifth
 * enumerated read in this codebase that omits {@code org_id}</strong>, a sibling of {@link
 * PlatformStatsRepository}, {@link PlatformQueueRepository}, {@code PlatformSearchRepository} and
 * {@code PlatformFunnelRepository}, under the same three rules:
 *
 * <ol>
 *   <li><strong>Platform-gated.</strong> Reached solely through {@code /api/admin/tickets} behind
 *       {@code AuthzHelper.requireSupportDesk} (ADMIN or SUPPORT). No org-scoped route may call it.
 *   <li><strong>Read-only.</strong> The desk's writes go through the org-scoped {@link
 *       SupportTicketRepository} once {@link #findOrgId} has named the tenant.
 *   <li><strong>Rows against a declared whitelist</strong> — {@link DeskTicketRow}: the ticket's
 *       summary, the tenant, the opener's display name. Never an email, never a body.
 * </ol>
 *
 * <p>The membership rule per status is written once, in {@code TicketDeskPredicates}, and consumed
 * here and by {@link PlatformStatsRepository#ticketCounts()} — the overview tile, the tab badge and
 * the list are the same number because they are the same predicate.
 */
public interface PlatformTicketRepository {

  /**
   * One page. {@code OPEN} is a queue — {@code blocking DESC, status_since ASC} (the merchant who
   * cannot sell first, then the longest-waiting); every other status is a ledger, newest activity
   * first. {@code orgId} null spans every tenant; an unknown id is an empty page, not an error.
   */
  List<DeskTicketRow> page(TicketStatus status, UUID orgId, int offset, int limit);

  long count(TicketStatus status, UUID orgId);

  /** The four counts plus blocking-open, optionally narrowed to one tenant. */
  TicketDeskCounts counts(UUID orgId);

  /** The tenant a ticket belongs to — the desk's way into the org-scoped repository. */
  Optional<UUID> findOrgId(UUID ticketId);
}
