package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A screenshot on a {@link TicketMessage}: an object key under the org's support prefix — never
 * bytes — plus what a list needs to say "2 attachments" without signing anything.
 */
public record TicketAttachment(
    UUID id,
    UUID orgId,
    UUID ticketId,
    UUID messageId,
    String objectKey,
    String contentType,
    String fileName,
    OffsetDateTime createdAt) {}
