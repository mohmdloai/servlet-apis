package com.loai.inventory.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.domain.model.OrgTimelineEntry;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry on {@code GET /api/admin/orgs/{orgId}/timeline} (slice 4).
 *
 * <p>{@code source} is on the wire so the client can say "this is an access event, not an
 * administrative one" without pattern-matching on verb strings.
 *
 * <p>{@code action} crosses <strong>verbatim</strong>. It is a {@code VARCHAR(64)} with no enum
 * behind it, so a new audited action ships without a migration; validating it here against a known
 * set would 500 on the first verb someone adds. Rendering the unknown is the client's job.
 */
public class AdminOrgTimelineEntryResponse {

  private final UUID id;
  private final OffsetDateTime at;
  private final String source;
  private final String action;
  private final Actor actor;
  private final JsonNode detail;

  private AdminOrgTimelineEntryResponse(
      UUID id, OffsetDateTime at, String source, String action, Actor actor, JsonNode detail) {
    this.id = id;
    this.at = at;
    this.source = source;
    this.action = action;
    this.actor = actor;
    this.detail = detail;
  }

  /**
   * The operator responsible. <strong>Absent when the id resolved to nothing</strong> — never
   * substituted with the caller or a placeholder. Attribution is the entire value of this surface.
   */
  public static class Actor {
    private final UUID id;
    private final String email;
    private final String displayName;

    Actor(UUID id, String email, String displayName) {
      this.id = id;
      this.email = email;
      this.displayName = displayName;
    }

    public UUID getId() {
      return id;
    }

    public String getEmail() {
      return email;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  /**
   * {@code detail} ships <strong>parsed</strong>, as the audit ledger already ships it ({@code
   * AuditAdminHandler} re-parses the JSONB to a nested object rather than sending a string).
   *
   * <p>There is deliberately no per-action whitelist. Both surfaces are {@code
   * requirePlatformRead}, both read the same rows, and having one redact what the other displays in
   * full is incoherence rather than caution. If these payloads ever need narrowing, that is one
   * change in one place, for both readers.
   */
  public static AdminOrgTimelineEntryResponse from(OrgTimelineEntry e, ObjectMapper mapper) {
    Actor actor =
        e.actorId() == null ? null : new Actor(e.actorId(), e.actorEmail(), e.actorDisplayName());
    JsonNode detail = null;
    if (e.detailJson() != null) {
      try {
        detail = mapper.readTree(e.detailJson());
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        // Unparseable stored payload is not worth failing a read over: the entry still carries the
        // verb, the actor and the timestamp, which is what the page is for.
        detail = null;
      }
    }
    return new AdminOrgTimelineEntryResponse(
        e.id(), e.at(), e.source().wire(), e.action(), actor, detail);
  }

  public UUID getId() {
    return id;
  }

  public OffsetDateTime getAt() {
    return at;
  }

  public String getSource() {
    return source;
  }

  public String getAction() {
    return action;
  }

  public Actor getActor() {
    return actor;
  }

  public JsonNode getDetail() {
    return detail;
  }
}
