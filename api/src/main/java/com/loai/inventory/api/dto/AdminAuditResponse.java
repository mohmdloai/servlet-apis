package com.loai.inventory.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.domain.model.PlatformAuditEntry;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the platform audit ledger (PG2). {@code detail} is the stored payload re-parsed into a
 * nested JSON object (null when the row carried none); the rest mirror the {@code platform_audit}
 * columns.
 */
public record AdminAuditResponse(
    UUID id,
    UUID actorId,
    String action,
    String targetType,
    UUID targetId,
    JsonNode detail,
    String sourceIp,
    String userAgent,
    OffsetDateTime createdAt) {

  public static AdminAuditResponse from(PlatformAuditEntry e, ObjectMapper mapper) {
    JsonNode detail = null;
    if (e.detailJson() != null) {
      try {
        detail = mapper.readTree(e.detailJson());
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        detail = null; // a malformed stored payload never breaks the read
      }
    }
    return new AdminAuditResponse(
        e.id(),
        e.actorId(),
        e.action(),
        e.targetType(),
        e.targetId(),
        detail,
        e.sourceIp(),
        e.userAgent(),
        e.createdAt());
  }
}
