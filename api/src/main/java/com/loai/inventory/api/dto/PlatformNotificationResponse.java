package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.service.NotificationService.UserFeedItem;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of the operator's feed ({@code GET /api/admin/notifications}): the org feed's row shape
 * exactly, plus {@code org {id, name}} — the console has no current org to imply it, and an
 * operator's rows span every tenant. Read-only outward projection (snake_case on the wire).
 */
public class PlatformNotificationResponse {

  /** The tenant the row concerns — {@code notification.org_id} is NOT NULL, so always present. */
  public record OrgRef(UUID id, String name) {}

  private UUID id;
  private String type;
  private String title;
  private String body;
  private String sourceType;
  private UUID sourceId;
  private String linkTarget;
  private OffsetDateTime createdAt;
  private OffsetDateTime readAt;
  private OffsetDateTime dismissedAt;
  private OrgRef org;

  private PlatformNotificationResponse() {}

  public static PlatformNotificationResponse from(UserFeedItem row) {
    Notification n = row.item().notification();
    PlatformNotificationResponse r = new PlatformNotificationResponse();
    r.id = n.getId();
    r.type = n.getType();
    r.title = n.getTitle();
    r.body = n.getBody();
    r.sourceType = n.getSourceType();
    r.sourceId = n.getSourceId();
    r.linkTarget = row.item().linkTarget();
    r.createdAt = n.getCreatedAt();
    r.readAt = row.item().readAt();
    r.dismissedAt = row.item().dismissedAt();
    r.org = new OrgRef(row.orgId(), row.orgName());
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  public String getBody() {
    return body;
  }

  public String getSourceType() {
    return sourceType;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public String getLinkTarget() {
    return linkTarget;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getReadAt() {
    return readAt;
  }

  public OffsetDateTime getDismissedAt() {
    return dismissedAt;
  }

  public OrgRef getOrg() {
    return org;
  }
}
