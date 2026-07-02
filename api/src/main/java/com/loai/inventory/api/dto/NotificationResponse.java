package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.Notification;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One in-app feed entry: the notification event plus this user's read/dismissed state and link
 * target. Read-only outward projection (Jackson serializes to snake_case).
 */
public class NotificationResponse {
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

  private NotificationResponse() {}

  public static NotificationResponse from(InAppFeedItem item) {
    Notification n = item.notification();
    NotificationResponse r = new NotificationResponse();
    r.id = n.getId();
    r.type = n.getType();
    r.title = n.getTitle();
    r.body = n.getBody();
    r.sourceType = n.getSourceType();
    r.sourceId = n.getSourceId();
    r.linkTarget = item.linkTarget();
    r.createdAt = n.getCreatedAt();
    r.readAt = item.readAt();
    r.dismissedAt = item.dismissedAt();
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
}
