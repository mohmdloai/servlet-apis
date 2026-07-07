package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A read projection of one {@code platform_audit} row (see {@code PlatformAuditEvent} for the write
 * contract). Unlike the write model this carries the database-assigned {@code id} and {@code
 * createdAt}, backing the audit-log read endpoint (PG2). {@code detailJson} is the raw JSON string
 * as stored; the API layer re-parses it into a nested object for the response.
 */
public record PlatformAuditEntry(
    UUID id,
    UUID actorId,
    String action,
    String targetType,
    UUID targetId,
    String detailJson,
    String sourceIp,
    String userAgent,
    OffsetDateTime createdAt) {}
