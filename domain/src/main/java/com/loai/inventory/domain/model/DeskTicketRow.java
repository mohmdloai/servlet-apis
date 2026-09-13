package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the desk inbox ({@code GET /api/admin/tickets}) — the cross-org read's field
 * whitelist, the {@code PlatformQueueRow} way: the ticket's summary, the tenant it belongs to
 * ({@link PlatformQueueOrg}: id, name, slug, the server-derived status) and the opener's display
 * name. Never an email, never a message body — those cross only on the single-ticket read.
 */
public record DeskTicketRow(
    UUID id,
    long number,
    TicketStatus status,
    TicketCategory category,
    boolean blocking,
    String subject,
    TicketRef ref,
    PlatformQueueOrg org,
    UUID openedById,
    String openedByName,
    OffsetDateTime openedAt,
    OffsetDateTime statusSince,
    OffsetDateTime lastActivityAt,
    int attachmentCount,
    String lastMessagePreview) {}
